package com.gachicraft.ironfrontiers.storage;

import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Bounded write-behind decorator for sector state. The latest snapshot per sector
 * replaces older pending snapshots. KPM overrides are explicit admin operations:
 * callers activate them only after the delegated database write succeeds.
 */
public final class RetryingStateStore implements StateStore {
    private static final int MAX_PENDING_SECTORS = 10_000;
    private static final int BATCH_SIZE = 100;

    private final Plugin plugin;
    private final StateStore delegate;
    private final CentralTimerScheduler timers;
    private final Object lock = new Object();
    private final Map<String, PendingWrite> pending = new LinkedHashMap<>();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean flushInProgress = new AtomicBoolean();
    private volatile boolean closed;
    private volatile int retryDelaySeconds = 5;
    private volatile long lastWarningNanos;

    public RetryingStateStore(Plugin plugin, StateStore delegate, CentralTimerScheduler timers) {
        this.plugin = plugin;
        this.delegate = delegate;
        this.timers = timers;
    }

    @Override
    public CompletableFuture<List<SectorPersistentState>> loadAll() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("StateStore is closed"));
        return delegate.loadAll();
    }

    @Override
    public CompletableFuture<List<SectorOverride>> loadOverrides() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("StateStore is closed"));
        return delegate.loadOverrides();
    }

    @Override
    public CompletableFuture<Void> saveOverride(SectorOverride override) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("StateStore is closed"));
        return delegate.saveOverride(override);
    }

    @Override
    public CompletableFuture<Void> save(SectorPersistentState state) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("StateStore is closed"));

        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (lock) {
            PendingWrite write = pending.get(state.sectorId());
            if (write == null) {
                if (pending.size() >= MAX_PENDING_SECTORS) {
                    result.completeExceptionally(new IllegalStateException(
                            "StateStore queue is full (" + MAX_PENDING_SECTORS + " sectors)"));
                    plugin.getLogger().severe("StateStore queue is full; refusing state write for " + state.sectorId());
                    return result;
                }
                write = new PendingWrite(state);
                pending.put(state.sectorId(), write);
            } else {
                write.revision++;
                write.state = state;
            }
            write.waiters.add(new Waiter(write.revision, result));
        }

        flushPending();
        return result;
    }

    private void flushPending() {
        if (closed || !plugin.isEnabled() || !flushInProgress.compareAndSet(false, true)) return;

        List<Snapshot> batch = new ArrayList<>(BATCH_SIZE);
        synchronized (lock) {
            for (PendingWrite write : pending.values()) {
                batch.add(new Snapshot(write, write.state, write.revision));
                if (batch.size() == BATCH_SIZE) break;
            }
        }

        if (batch.isEmpty()) {
            flushInProgress.set(false);
            return;
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] writes = batch.stream().map(this::persistSnapshot)
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(writes).whenComplete((ignored, aggregateError) -> {
            flushInProgress.set(false);
            if (closed || !plugin.isEnabled()) return;

            boolean failed = false;
            for (CompletableFuture<Boolean> write : writes) {
                try { if (!write.join()) failed = true; }
                catch (RuntimeException exception) { failed = true; }
            }

            boolean hasPending;
            synchronized (lock) { hasPending = !pending.isEmpty(); }
            if (!hasPending) retryDelaySeconds = 5;
            else if (failed) scheduleRetry();
            else flushPending();
        });
    }

    private CompletableFuture<Boolean> persistSnapshot(Snapshot snapshot) {
        try {
            return delegate.save(snapshot.state).handle((ignored, error) -> {
                if (error == null) {
                    completeSnapshot(snapshot);
                    return true;
                }
                warnDatabaseUnavailable(error);
                return false;
            });
        } catch (RuntimeException exception) {
            warnDatabaseUnavailable(exception);
            return CompletableFuture.completedFuture(false);
        }
    }

    private void completeSnapshot(Snapshot snapshot) {
        List<CompletableFuture<Void>> completed = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            PendingWrite current = pending.get(snapshot.state.sectorId());
            if (current != snapshot.pending) return;

            Iterator<Waiter> iterator = current.waiters.iterator();
            while (iterator.hasNext()) {
                Waiter waiter = iterator.next();
                if (waiter.revision <= snapshot.revision) {
                    completed.add(waiter.future);
                    iterator.remove();
                }
            }
            if (current.revision == snapshot.revision) {
                pending.remove(snapshot.state.sectorId());
            }
        }
        completed.forEach(future -> future.complete(null));
    }

    private void scheduleRetry() {
        if (closed || !plugin.isEnabled() || !retryScheduled.compareAndSet(false, true)) return;
        Runnable schedule = () -> {
            if (closed || !plugin.isEnabled()) {
                retryScheduled.set(false);
                return;
            }
            int delay = retryDelaySeconds;
            retryDelaySeconds = Math.min(retryDelaySeconds * 2, 300);
            timers.schedule("state-store-retry", "database", delay * 20L, () -> {
                retryScheduled.set(false);
                flushPending();
            });
        };
        if (Bukkit.isPrimaryThread()) schedule.run();
        else {
            try {
                Bukkit.getScheduler().runTask(plugin, schedule);
            } catch (IllegalStateException exception) {
                retryScheduled.set(false);
                if (!closed) plugin.getLogger().warning("Could not schedule database retry: " + exception.getMessage());
            }
        }
    }

    private void warnDatabaseUnavailable(Throwable error) {
        long now = System.nanoTime();
        if (now - lastWarningNanos < 60_000_000_000L) return;
        lastWarningNanos = now;
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        plugin.getLogger().log(Level.WARNING,
                "MySQL is unavailable; sector state writes are queued for retry", cause);
    }

    public int pendingSectors() {
        synchronized (lock) { return pending.size(); }
    }

    @Override
    public void close() {
        closed = true;
        List<CompletableFuture<Void>> waiters = new ArrayList<>();
        synchronized (lock) {
            for (PendingWrite write : pending.values()) {
                for (Waiter waiter : write.waiters) waiters.add(waiter.future);
            }
            pending.clear();
        }
        IllegalStateException failure = new IllegalStateException(
                "Plugin disabled before queued state writes could be persisted");
        waiters.forEach(future -> future.completeExceptionally(failure));
        delegate.close();
    }

    private static final class PendingWrite {
        private SectorPersistentState state;
        private long revision;
        private final List<Waiter> waiters = new ArrayList<>();
        private PendingWrite(SectorPersistentState state) { this.state = state; }
    }

    private record Waiter(long revision, CompletableFuture<Void> future) { }
    private record Snapshot(PendingWrite pending, SectorPersistentState state, long revision) { }
}

package com.gachicraft.ironfrontiers.util;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Single Bukkit ticker for keyed timers. Timers store remaining ticks and can
 * be paused/resumed as a group without scheduling a Bukkit task per timer.
 */
public final class CentralTimerScheduler implements AutoCloseable {
    private final Plugin plugin;
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(
            Comparator.comparingLong((Entry entry) -> entry.dueTick).thenComparingLong(entry -> entry.sequence));
    private final Map<String, Entry> active = new HashMap<>();
    private final Map<String, Map<String, Entry>> frozen = new HashMap<>();
    private final Set<String> frozenGroups = new HashSet<>();
    private BukkitTask ticker;
    private long tick;
    private long sequence;

    public CentralTimerScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void start() {
        if (ticker != null) throw new IllegalStateException("Timer scheduler already started");
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::runTick, 1L, 1L);
    }

    public void schedule(String key, long delayTicks, Runnable action) {
        schedule(key, "default", delayTicks, action);
    }

    public void schedule(String key, String group, long delayTicks, Runnable action) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(action, "action");
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
        cancel(key);
        Entry entry = new Entry(key, group, tick + delayTicks, delayTicks, ++sequence, action);
        active.put(key, entry);
        if (frozenGroups.contains(group)) {
            frozen.computeIfAbsent(group, ignored -> new HashMap<>()).put(key, entry);
        } else {
            queue.add(entry);
        }
    }

    public boolean cancel(String key) {
        Entry removed = active.remove(key);
        if (removed == null) return false;
        Map<String, Entry> group = frozen.get(removed.group);
        if (group != null) {
            group.remove(key);
            if (group.isEmpty()) frozen.remove(removed.group);
        }
        return true;
    }

    public void freeze(String group) {
        if (!frozenGroups.add(group)) return;
        Iterator<Entry> iterator = queue.iterator();
        Map<String, Entry> paused = frozen.computeIfAbsent(group, ignored -> new HashMap<>());
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.group.equals(group) && active.get(entry.key) == entry) {
                entry.remainingTicks = Math.max(0L, entry.dueTick - tick);
                paused.put(entry.key, entry);
                iterator.remove();
            }
        }
    }

    public void resume(String group) {
        if (!frozenGroups.remove(group)) return;
        Map<String, Entry> paused = frozen.remove(group);
        if (paused == null) return;
        for (Entry entry : paused.values()) {
            if (active.get(entry.key) == entry) {
                entry.dueTick = tick + entry.remainingTicks;
                queue.add(entry);
            }
        }
    }

    public int size() { return active.size(); }

    private void runTick() {
        tick++;
        while (!queue.isEmpty() && queue.peek().dueTick <= tick) {
            Entry entry = queue.poll();
            if (active.get(entry.key) != entry) continue;
            active.remove(entry.key);
            try {
                entry.action.run();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Timer '" + entry.key + "' failed: " + exception.getMessage());
                if (plugin.getConfig().getBoolean("debug", false)) exception.printStackTrace();
            }
        }
    }

    @Override
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        queue.clear();
        active.clear();
        frozen.clear();
        frozenGroups.clear();
    }

    private static final class Entry {
        private final String key;
        private final String group;
        private long dueTick;
        private long remainingTicks;
        private final long sequence;
        private final Runnable action;

        private Entry(String key, String group, long dueTick, long remainingTicks, long sequence, Runnable action) {
            this.key = key;
            this.group = group;
            this.dueTick = dueTick;
            this.remainingTicks = remainingTicks;
            this.sequence = sequence;
            this.action = action;
        }
    }
}

package com.gachicraft.ironfrontiers.util;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Single Bukkit ticker for keyed timers; individual timers never create Bukkit tasks. */
public final class CentralTimerScheduler implements AutoCloseable {
    private final Plugin plugin;
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.dueTick).thenComparingLong(e -> e.sequence));
    private final Map<String, Entry> active = new HashMap<>();
    private final Map<String, Map<String, Entry>> frozen = new HashMap<>();
    private final Set<String> frozenGroups = new HashSet<>();
    private BukkitTask ticker;
    private long tick;
    private long sequence;

    public CentralTimerScheduler(Plugin plugin) { this.plugin = Objects.requireNonNull(plugin); }

    public void start() {
        if (ticker != null) throw new IllegalStateException("Scheduler already started");
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::runTick, 1L, 1L);
    }

    public void schedule(String key, long delayTicks, Runnable action) {
        schedule(key, "default", delayTicks, action);
    }

    public void schedule(String key, String group, long delayTicks, Runnable action) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(group);
        Objects.requireNonNull(action);
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be non-negative");
        cancel(key);
        Entry entry = new Entry(key, group, tick + delayTicks, delayTicks, ++sequence, action);
        active.put(key, entry);
        if (frozenGroups.contains(group)) frozen.computeIfAbsent(group, ignored -> new HashMap<>()).put(key, entry);
        else queue.add(entry);
    }

    public boolean cancel(String key) {
        Entry entry = active.remove(key);
        if (entry == null) return false;
        Map<String, Entry> group = frozen.get(entry.group);
        if (group != null) {
            group.remove(key);
            if (group.isEmpty()) frozen.remove(entry.group);
        }
        return true; // stale priority-queue entries are ignored lazily
    }

    public void freeze(String group) {
        if (!frozenGroups.add(group)) return;
        Map<String, Entry> paused = frozen.computeIfAbsent(group, ignored -> new HashMap<>());
        Iterator<Entry> iterator = queue.iterator();
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
        if (ticker != null) { ticker.cancel(); ticker = null; }
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
            this.key = key; this.group = group; this.dueTick = dueTick;
            this.remainingTicks = remainingTicks; this.sequence = sequence; this.action = action;
        }
    }
}

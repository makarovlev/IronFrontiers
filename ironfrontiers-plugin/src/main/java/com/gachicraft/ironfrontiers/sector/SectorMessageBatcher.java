package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.message.Messages;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

/** Batches destruction and loss messages for players currently in war worlds. */
final class SectorMessageBatcher implements AutoCloseable {
    private static final String DESTRUCTION_KEY = "sector-message-destruction";
    private static final String LOST_KEY = "sector-message-lost";

    private final Plugin plugin;
    private final Server server;
    private final ConfigManager configs;
    private final CentralTimerScheduler timers;
    private final List<Pending> destruction = new ArrayList<>();
    private final List<Pending> lost = new ArrayList<>();
    private boolean closed;

    SectorMessageBatcher(Plugin plugin, Server server, ConfigManager configs,
                         CentralTimerScheduler timers) {
        this.plugin = plugin;
        this.server = server;
        this.configs = configs;
        this.timers = timers;
    }

    void destructionStarted(SectorDefinition sector, long timeLeftSeconds) {
        add(destruction, DESTRUCTION_KEY, new Pending(sector.displayName(), timeLeftSeconds));
    }

    void sectorLost(SectorDefinition sector) {
        add(lost, LOST_KEY, new Pending(sector.displayName(), 0));
    }

    void captured(SectorDefinition sector) {
        send("game.captured", Map.of("sector", sector.displayName()));
    }

    void destructionCancelled(SectorDefinition sector) {
        send("game.destruction_cancelled", Map.of("sector", sector.displayName()));
    }

    private void add(List<Pending> target, String timerKey, Pending pending) {
        if (closed) return;
        boolean first = target.isEmpty();
        target.add(pending);
        if (first) {
            int delay = Math.max(1, configs.config().getInt("messages.batch_window_seconds", 3));
            timers.schedule(timerKey, "sector-messages", delay * 20L,
                    () -> flush(target, timerKey));
        }
    }

    private void flush(List<Pending> target, String timerKey) {
        if (closed || target.isEmpty()) return;
        List<Pending> batch = List.copyOf(target);
        target.clear();

        if (DESTRUCTION_KEY.equals(timerKey)) {
            Map<Long, List<Pending>> byTimer = new TreeMap<>();
            for (Pending item : batch) {
                byTimer.computeIfAbsent(item.timeLeftSeconds, ignored -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<Long, List<Pending>> group : byTimer.entrySet()) {
                List<Pending> events = group.getValue();
                String key = events.size() == 1
                        ? "game.destruction_started_single"
                        : "game.destruction_started_multi";
                String names = joinNames(events);
                send(key, Map.of(
                        "sector", names,
                        "sectors", names,
                        "time", formatTime(group.getKey())));
            }
        } else {
            String key = batch.size() == 1 ? "game.lost_single" : "game.lost_multi";
            String names = joinNames(batch);
            send(key, Map.of("sector", names, "sectors", names));
        }
    }

    private void send(String key, Map<String, String> placeholders) {
        Messages messages = new Messages(configs.messages());
        // Sector state affects the whole game server; notify every online player,
        // including players in lobby, tutorial, and other non-war worlds.
        for (Player player : server.getOnlinePlayers()) {
            messages.send(player, key, placeholders);
        }
    }

    private static String joinNames(List<Pending> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.getFirst().displayName;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) builder.append(i == items.size() - 1 ? " и " : ", ");
            builder.append(items.get(i).displayName);
        }
        return builder.toString();
    }

    private static String formatTime(long seconds) {
        long safe = Math.max(0, seconds);
        if (safe >= 3600) {
            long hours = safe / 3600;
            long minutes = (safe % 3600) / 60;
            return hours + " " + plural(hours, "час", "часа", "часов")
                    + (minutes == 0 ? "" : " " + minutes + " " + plural(minutes, "минута", "минуты", "минут"));
        }
        if (safe >= 60) {
            long minutes = safe / 60;
            long remainder = safe % 60;
            return minutes + " " + plural(minutes, "минута", "минуты", "минут")
                    + (remainder == 0 ? "" : " " + remainder + " " + plural(remainder, "секунда", "секунды", "секунд"));
        }
        return safe + " " + plural(safe, "секунда", "секунды", "секунд");
    }

    private static String plural(long value, String one, String few, String many) {
        long mod100 = value % 100;
        if (mod100 >= 11 && mod100 <= 14) return many;
        return switch ((int) (value % 10)) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }

    @Override
    public void close() {
        closed = true;
        timers.cancel(DESTRUCTION_KEY);
        timers.cancel(LOST_KEY);
        destruction.clear();
        lost.clear();
    }

    private record Pending(String displayName, long timeLeftSeconds) { }
}

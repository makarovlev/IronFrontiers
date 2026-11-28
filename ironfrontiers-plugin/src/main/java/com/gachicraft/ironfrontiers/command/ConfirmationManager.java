package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.message.Messages;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player one-shot confirmation commands, accepted by /if agree or chat word "agree". */
public final class ConfirmationManager implements Listener, AutoCloseable {
    private static final String TIMER_PREFIX = "confirmation-";

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final CentralTimerScheduler timers;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private boolean closed;

    public ConfirmationManager(JavaPlugin plugin, ConfigManager configs,
                               CentralTimerScheduler timers) {
        this.plugin = plugin;
        this.configs = configs;
        this.timers = timers;
    }

    public int request(Player player, Runnable action) {
        if (closed) throw new IllegalStateException("Confirmation manager is closed");
        int timeout = Math.max(1, configs.config().getInt("confirmations.timeout_seconds", 30));
        UUID playerId = player.getUniqueId();
        pending.put(playerId, new Pending(action));
        timers.schedule(timerKey(playerId), "confirmations", timeout * 20L, () -> {
            Pending expired = pending.remove(playerId);
            if (expired != null) {
                Player online = Bukkit.getPlayer(playerId);
                if (online != null) send(online, "confirm.expired");
            }
        });
        return timeout;
    }

    public boolean accept(Player player) {
        Pending request = pending.remove(player.getUniqueId());
        if (request == null) {
            send(player, "confirm.none_pending");
            return false;
        }
        timers.cancel(timerKey(player.getUniqueId()));
        send(player, "confirm.accepted");
        try {
            request.action.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Confirmed admin action failed for " + player.getName()
                    + ": " + exception.getMessage());
            exception.printStackTrace();
        }
        return true;
    }

    public boolean hasPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!"agree".equalsIgnoreCase(event.getMessage().trim())
                || !pending.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) accept(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pending.remove(playerId);
        timers.cancel(timerKey(playerId));
    }

    private void send(Player player, String key) {
        new Messages(configs.messages()).send(player, key);
    }

    private static String timerKey(UUID playerId) {
        return TIMER_PREFIX + playerId;
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        // Cancel only timers owned by this manager.
        for (String key : pending.keySet()) timers.cancel(timerKey(UUID.fromString(key)));
    }

    private record Pending(Runnable action) { }
}

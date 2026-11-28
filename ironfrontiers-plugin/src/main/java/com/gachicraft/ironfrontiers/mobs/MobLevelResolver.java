package com.gachicraft.ironfrontiers.mobs;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Resolves optional MythicMobs levels; PlaceholderAPI is accessed reflectively and remains optional. */
public final class MobLevelResolver {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Method placeholderMethod;

    public MobLevelResolver(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public int resolve(MobEntry entry, Player player) {
        if (entry.levelMin() == null) return 1;
        int minimum = entry.levelMin();
        Object scale = entry.levelScale();
        if (scale == null) return clamp(minimum, entry);

        Integer value = null;
        if (scale instanceof Number number) {
            value = parse(number.toString());
        } else if (scale instanceof String placeholder) {
            value = parse(resolvePlaceholder(player, placeholder));
        }

        if (value == null) {
            warnOnce(entry, "Could not resolve level.scale; using level.min=" + minimum);
            return clamp(minimum, entry);
        }
        return clamp(value, entry);
    }

    private String resolvePlaceholder(Player player, String input) {
        if (!input.contains("%")) return input;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        try {
            Method method = placeholderMethod;
            if (method == null) {
                Class<?> type = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                        plugin.getClass().getClassLoader());
                method = type.getMethod("setPlaceholders", Player.class, String.class);
                placeholderMethod = method;
            }
            Object result = method.invoke(null, player, input);
            return result instanceof String text ? text : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    private static int clamp(int value, MobEntry entry) {
        int bounded = Math.max(entry.levelMin(), value);
        if (entry.levelMax() != null) bounded = Math.min(entry.levelMax(), bounded);
        return Math.max(1, bounded);
    }

    private static Integer parse(String value) {
        if (value == null || value.isBlank() || value.contains("%")) return null;
        try {
            return new BigDecimal(value.trim()).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private void warnOnce(MobEntry entry, String message) {
        String key = entry.entryId() + "|" + entry.mobId() + "|" + message;
        if (warned.add(key)) logger.warning("Mob cluster level " + entry.entryId() + ": " + message);
    }
}

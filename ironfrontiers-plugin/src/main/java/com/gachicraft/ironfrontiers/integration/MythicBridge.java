package com.gachicraft.ironfrontiers.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Optional MythicMobs adapter. The API is resolved only when MythicMobs is
 * installed, so the core plugin has no hard linkage to a particular MythicMobs jar.
 */
public final class MythicBridge extends PluginPresenceBridge {
    public enum MobLookup {
        FOUND,
        NOT_FOUND,
        PLUGIN_MISSING,
        API_UNSUPPORTED
    }

    private static final String API_CLASS = "io.lumine.mythic.bukkit.MythicBukkit";
    private final PluginManager pluginManager;

    public MythicBridge(PluginManager manager) {
        super(manager, "MythicMobs");
        this.pluginManager = manager;
    }

    /**
     * Validates a MythicMobs mob identifier via the documented 5.x lookup.
     * A signature mismatch is reported as API_UNSUPPORTED rather than treating
     * a valid mob as missing. No MythicMobs classes are loaded in single-plugin mode.
     */
    public MobLookup validateMobId(String mobId) {
        Plugin mythicPlugin = pluginManager.getPlugin("MythicMobs");
        if (mythicPlugin == null || !mythicPlugin.isEnabled()) {
            return MobLookup.PLUGIN_MISSING;
        }

        try {
            ClassLoader loader = mythicPlugin.getClass().getClassLoader();
            Class<?> mythicBukkit = Class.forName(API_CLASS, true, loader);
            Method instanceMethod = mythicBukkit.getMethod("inst");
            Object api = instanceMethod.invoke(null);
            Object mobManager = api.getClass().getMethod("getMobManager").invoke(api);
            Object result = mobManager.getClass().getMethod("getMythicMob", String.class).invoke(mobManager, mobId);

            if (result instanceof Optional<?> optional) {
                return optional.isPresent() ? MobLookup.FOUND : MobLookup.NOT_FOUND;
            }
            return result == null ? MobLookup.NOT_FOUND : MobLookup.FOUND;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return MobLookup.API_UNSUPPORTED;
        }
    }
}

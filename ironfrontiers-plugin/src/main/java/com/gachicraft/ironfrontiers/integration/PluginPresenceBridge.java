package com.gachicraft.ironfrontiers.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Objects;

/** Base class for optional-plugin adapters without compile-time third-party links. */
public abstract class PluginPresenceBridge {
    private final PluginManager manager;
    private final String pluginName;

    protected PluginPresenceBridge(PluginManager manager, String pluginName) {
        this.manager = Objects.requireNonNull(manager);
        this.pluginName = Objects.requireNonNull(pluginName);
    }

    public final boolean isAvailable() {
        Plugin plugin = manager.getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    public final String pluginName() { return pluginName; }
}

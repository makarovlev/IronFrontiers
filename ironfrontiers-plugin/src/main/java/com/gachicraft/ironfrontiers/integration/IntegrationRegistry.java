package com.gachicraft.ironfrontiers.integration;

import org.bukkit.plugin.PluginManager;
import java.util.List;

/** Discovers soft dependencies and reports missing integrations without disabling the core. */
public final class IntegrationRegistry {
    private final List<PluginPresenceBridge> bridges;

    public IntegrationRegistry(PluginManager manager) {
        bridges = List.of(new PapiBridge(manager), new MythicBridge(manager), new MmoItemsBridge(manager),
                new ItemsAdderBridge(manager), new CitizensBridge(manager), new WorldEditBridge(manager),
                new FastAsyncWorldEditBridge(manager));
    }

    public void logAvailability(java.util.logging.Logger logger) {
        for (PluginPresenceBridge bridge : bridges) {
            if (bridge.isAvailable()) logger.info(bridge.pluginName() + " integration detected");
            else logger.warning(bridge.pluginName() + " is not installed; related functionality will be disabled");
        }
    }

    public boolean isAvailable(String pluginName) {
        return bridges.stream().filter(b -> b.pluginName().equalsIgnoreCase(pluginName))
                .findFirst().map(PluginPresenceBridge::isAvailable).orElse(false);
    }
}

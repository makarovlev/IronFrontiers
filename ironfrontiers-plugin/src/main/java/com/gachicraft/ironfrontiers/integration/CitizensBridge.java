package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for Citizens. */
public final class CitizensBridge extends PluginPresenceBridge {
    public CitizensBridge(PluginManager manager) { super(manager, "Citizens"); }
}

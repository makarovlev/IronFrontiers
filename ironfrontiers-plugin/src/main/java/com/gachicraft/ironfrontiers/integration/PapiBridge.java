package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for PlaceholderAPI. */
public final class PapiBridge extends PluginPresenceBridge {
    public PapiBridge(PluginManager manager) { super(manager, "PlaceholderAPI"); }
}

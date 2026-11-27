package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for WorldEdit. */
public final class WorldEditBridge extends PluginPresenceBridge {
    public WorldEditBridge(PluginManager manager) { super(manager, "WorldEdit"); }
}

package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for FastAsyncWorldEdit. */
public final class FastAsyncWorldEditBridge extends PluginPresenceBridge {
    public FastAsyncWorldEditBridge(PluginManager manager) { super(manager, "FastAsyncWorldEdit"); }
}

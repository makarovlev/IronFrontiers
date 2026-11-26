package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for MMOItems. */
public final class MmoItemsBridge extends PluginPresenceBridge {
    public MmoItemsBridge(PluginManager manager) { super(manager, "MMOItems"); }
}

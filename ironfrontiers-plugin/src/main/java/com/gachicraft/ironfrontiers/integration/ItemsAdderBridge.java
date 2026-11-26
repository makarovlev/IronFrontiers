package com.gachicraft.ironfrontiers.integration;
import org.bukkit.plugin.PluginManager;
/** Optional integration boundary for ItemsAdder. */
public final class ItemsAdderBridge extends PluginPresenceBridge {
    public ItemsAdderBridge(PluginManager manager) { super(manager, "ItemsAdder"); }
}

package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers command executor and tab completer from plugin.yml. */
public final class CommandRegistrar {
    private CommandRegistrar() { }

    public static void register(JavaPlugin plugin, ConfigManager configs) {
        var command = plugin.getCommand("if");
        if (command == null) throw new IllegalStateException("Command 'if' is missing from plugin.yml");
        IfCommand handler = new IfCommand(configs);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}

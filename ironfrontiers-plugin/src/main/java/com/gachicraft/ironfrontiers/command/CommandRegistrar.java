package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Registers the command executor early so it can explain database startup failures. */
public final class CommandRegistrar {
    private CommandRegistrar() { }

    public static void register(JavaPlugin plugin, ConfigManager configs,
                                Supplier<SectorRuntimeController> controller,
                                BooleanSupplier databaseReady) {
        var command = plugin.getCommand("if");
        if (command == null) throw new IllegalStateException("Command 'if' is missing from plugin.yml");
        IfCommand handler = new IfCommand(plugin, configs, controller, databaseReady);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}

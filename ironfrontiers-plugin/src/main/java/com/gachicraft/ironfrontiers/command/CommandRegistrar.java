package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import com.gachicraft.ironfrontiers.selection.SelectionManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Registers the administrative command handler and its supporting managers. */
public final class CommandRegistrar {
    private CommandRegistrar() { }

    public static void register(JavaPlugin plugin, ConfigManager configs,
                                Supplier<SectorRuntimeController> controller,
                                BooleanSupplier databaseReady,
                                SelectionManager selection,
                                ConfirmationManager confirmations) {
        var command = plugin.getCommand("if");
        if (command == null) throw new IllegalStateException("Command 'if' is missing from plugin.yml");
        IfCommand handler = new IfCommand(plugin, configs, controller, databaseReady,
                selection, confirmations);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}

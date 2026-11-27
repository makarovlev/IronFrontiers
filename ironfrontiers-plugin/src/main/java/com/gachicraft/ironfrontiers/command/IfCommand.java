package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.api.SectorState;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.config.ReloadReport;
import com.gachicraft.ironfrontiers.message.Messages;
import com.gachicraft.ironfrontiers.sector.SectorDefinition;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Administrative entry point for reload, sector overrides and stage-two debug tools. */
public final class IfCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final Supplier<SectorRuntimeController> controllerSupplier;
    private final BooleanSupplier databaseReady;

    public IfCommand(JavaPlugin plugin, ConfigManager configs,
                     Supplier<SectorRuntimeController> controllerSupplier,
                     BooleanSupplier databaseReady) {
        this.plugin = plugin;
        this.configs = configs;
        this.controllerSupplier = controllerSupplier;
        this.databaseReady = databaseReady;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = new Messages(configs.messages());
        if (!sender.hasPermission("ironfrontiers.admin")) {
            messages.send(sender, "general.no_permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "general.usage.root");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            ReloadReport report = configs.reload();
            Messages updated = new Messages(configs.messages());
            updated.send(sender, "general.reload_done");
            for (String path : report.restartRequired()) {
                updated.send(sender, "general.reload_restart_required", Map.of("param", path));
            }
            SectorRuntimeController controller = controllerSupplier.get();
            if (databaseReady.getAsBoolean() && controller != null) {
                try {
                    controller.reloadDefinitions();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Sector runtime reload failed: " + exception.getMessage());
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            handleDebug(sender, args, messages);
            return true;
        }

        if (args[0].equalsIgnoreCase("sectors")) {
            handleSectors(sender, args, messages);
            return true;
        }

        messages.send(sender, "general.unknown_subcommand");
        return true;
    }

    private void handleDebug(CommandSender sender, String[] args, Messages messages) {
        if (!configs.config().getBoolean("debug", false)) {
            messages.send(sender, "debug.disabled");
            return;
        }
        SectorRuntimeController controller = requireController(messages, sender);
        if (controller == null) return;

        if (args.length >= 2 && args[1].equalsIgnoreCase("kill")) {
            if (args.length < 4 || args.length > 5) {
                messages.send(sender, "debug.usage_kill");
                return;
            }
            int count;
            int weightTenths = 10;
            try {
                count = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                messages.send(sender, "debug.invalid_count");
                return;
            }
            if (args.length == 5) {
                Integer parsed = parseTenths(args[4]);
                if (parsed == null) {
                    messages.send(sender, "debug.invalid_weight");
                    return;
                }
                weightTenths = parsed;
            }
            if (count < 1 || count > 10_000) {
                messages.send(sender, "debug.invalid_count");
                return;
            }
            if (!controller.debugKill(args[2], count, weightTenths)) {
                messages.send(sender, "sector.not_found", Map.of("sector", args[2]));
                return;
            }
            messages.send(sender, "debug.kill_added", Map.of(
                    "sector", args[2], "count", Integer.toString(count),
                    "weight", BigDecimal.valueOf(weightTenths, 1).stripTrailingZeros().toPlainString()));
            return;
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("state")) {
            SectorState state;
            try {
                state = SectorState.valueOf(args[3].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                messages.send(sender, "debug.invalid_state");
                return;
            }
            if (!controller.debugSetState(args[2], state)) {
                messages.send(sender, "sector.not_found", Map.of("sector", args[2]));
                return;
            }
            messages.send(sender, "debug.state_set",
                    Map.of("sector", args[2], "state", state.name()));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("state")) {
            messages.send(sender, "debug.usage_state");
        } else {
            messages.send(sender, "debug.usage_kill");
        }
    }

    private void handleSectors(CommandSender sender, String[] args, Messages messages) {
        if (args.length != 5 || !args[1].equalsIgnoreCase("set")) {
            messages.send(sender, "general.usage.sectors");
            return;
        }
        SectorRuntimeController controller = requireController(messages, sender);
        if (controller == null) return;

        String sector = args[2];
        String threshold = args[3].toLowerCase(Locale.ROOT);
        if (!threshold.equals("capture") && !threshold.equals("held")) {
            messages.send(sender, "sector.set_invalid");
            return;
        }
        if (!controller.registry().contains(sector)) {
            messages.send(sender, "sector.not_found", Map.of("sector", sector));
            return;
        }
        Integer tenths = parseTenths(args[4]);
        if (tenths == null) {
            messages.send(sender, "sector.set_invalid");
            return;
        }

        controller.setThresholdOverride(sector, threshold, tenths).whenComplete((ignored, error) -> {
            Runnable reply = () -> {
                Messages current = new Messages(configs.messages());
                if (error != null) {
                    current.send(sender, "sector.set_invalid");
                    plugin.getLogger().warning("Could not save " + threshold + " override for " + sector
                            + ": " + error.getMessage());
                    return;
                }
                current.send(sender, "sector.set_done", Map.of(
                        "sector", sector, "what", threshold,
                        "value", BigDecimal.valueOf(tenths, 1).stripTrailingZeros().toPlainString()));
            };
            if (Bukkit.isPrimaryThread()) reply.run();
            else plugin.getServer().getScheduler().runTask(plugin, reply);
        });
    }

    private SectorRuntimeController requireController(Messages messages, CommandSender sender) {
        SectorRuntimeController controller = controllerSupplier.get();
        if (!databaseReady.getAsBoolean() || controller == null) {
            messages.send(sender, "general.db_unavailable");
            return null;
        }
        return controller;
    }

    private static Integer parseTenths(String text) {
        try {
            BigDecimal decimal = new BigDecimal(text.trim());
            if (decimal.signum() <= 0 || decimal.scale() > 1) return null;
            return decimal.movePointRight(1).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ironfrontiers.admin")) return List.of();

        if (args.length == 1) {
            return filter(List.of("reload", "debug", "sectors"), args[0]);
        }

        if (args[0].equalsIgnoreCase("debug") && configs.config().getBoolean("debug", false)) {
            SectorRuntimeController controller = controllerSupplier.get();
            if (args.length == 2) return filter(List.of("kill", "state"), args[1]);
            if (controller == null || !databaseReady.getAsBoolean()) return List.of();

            if (args.length == 3 && (args[1].equalsIgnoreCase("kill") || args[1].equalsIgnoreCase("state"))) {
                return filter(controller.registry().sectors().stream().map(SectorDefinition::id).toList(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("state")) {
                return filter(Arrays.stream(SectorState.values()).map(Enum::name).toList(), args[3]);
            }
        }

        if (args[0].equalsIgnoreCase("sectors")) {
            SectorRuntimeController controller = controllerSupplier.get();
            if (args.length == 2) return filter(List.of("set"), args[1]);
            if (args.length == 3 && controller != null && databaseReady.getAsBoolean()) {
                return filter(controller.registry().sectors().stream().map(SectorDefinition::id).toList(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                return filter(List.of("capture", "held"), args[3]);
            }
        }

        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}

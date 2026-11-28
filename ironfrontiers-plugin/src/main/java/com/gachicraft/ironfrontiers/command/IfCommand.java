package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.api.SectorState;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.config.ReloadReport;
import com.gachicraft.ironfrontiers.geometry.GridPoint;
import com.gachicraft.ironfrontiers.message.Messages;
import com.gachicraft.ironfrontiers.sector.SectorDefinition;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeState;
import com.gachicraft.ironfrontiers.sector.SectorStatePolicy;
import com.gachicraft.ironfrontiers.sector.SurfaceResolver;
import com.gachicraft.ironfrontiers.selection.SelectionManager;
import com.gachicraft.ironfrontiers.util.YamlTextEditor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Administrative entry point for reload, sector management and stage-two debug tools. */
public final class IfCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final Supplier<SectorRuntimeController> controllerSupplier;
    private final BooleanSupplier databaseReady;
    private final SelectionManager selection;
    private final ConfirmationManager confirmations;

    public IfCommand(JavaPlugin plugin, ConfigManager configs,
                     Supplier<SectorRuntimeController> controllerSupplier,
                     BooleanSupplier databaseReady, SelectionManager selection,
                     ConfirmationManager confirmations) {
        this.plugin = plugin;
        this.configs = configs;
        this.controllerSupplier = controllerSupplier;
        this.databaseReady = databaseReady;
        this.selection = selection;
        this.confirmations = confirmations;
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

        if (args[0].equalsIgnoreCase("agree") && args.length == 1) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "general.player_only");
                return true;
            }
            confirmations.accept(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            SectorRuntimeController controller = controllerSupplier.get();
            Set<String> protectedSectors = databaseReady.getAsBoolean() && controller != null
                    ? controller.nonEnemySectorIds() : null;
            ReloadReport report = configs.reload(protectedSectors);
            Messages updated = new Messages(configs.messages());
            updated.send(sender, "general.reload_done");
            for (String path : report.restartRequired()) {
                updated.send(sender, "general.reload_restart_required", Map.of("param", path));
            }
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
                    "weight", formatTenths(weightTenths)));
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
        if (args.length < 2) {
            messages.send(sender, "general.usage.sectors");
            return;
        }
        SectorRuntimeController controller = requireController(messages, sender);
        if (controller == null) return;

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (args.length != 2) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                messages.send(sender, "sector.list_header");
                for (SectorDefinition sector : controller.registry().sectors()) {
                    SectorRuntimeState state = controller.state(sector.id()).orElse(null);
                    if (state == null) continue;
                    messages.send(sender, "sector.line", Map.of(
                            "sector", sector.id(),
                            "state", state.state().name(),
                            "kpm", formatKpm(controller.kpmTracker().kpm(sector.id()))));
                }
            }
            case "info" -> {
                if (args.length != 3) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                SectorDefinition sector = findSector(controller, args[2], messages, sender);
                if (sector != null) showInfo(sender, controller, sector);
            }
            case "tp" -> {
                if (args.length != 3) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "general.player_only");
                    return;
                }
                SectorDefinition sector = findSector(controller, args[2], messages, sender);
                if (sector != null) teleport(player, sector);
            }
            case "show" -> {
                if (args.length != 3) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "general.player_only");
                    return;
                }
                SectorDefinition sector = findSector(controller, args[2], messages, sender);
                if (sector != null) selection.show(player, sector);
            }
            case "create" -> {
                if (args.length != 3) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "general.player_only");
                    return;
                }
                if (configs.config().getBoolean("multiserver.enabled", false)) {
                    messages.send(sender, "general.multiserver_forbidden");
                    return;
                }
                selection.begin(player, args[2]);
            }
            case "delete" -> {
                if (args.length != 3) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "general.player_only");
                    return;
                }
                if (configs.config().getBoolean("multiserver.enabled", false)) {
                    messages.send(sender, "general.multiserver_forbidden");
                    return;
                }
                SectorDefinition sector = findSector(controller, args[2], messages, sender);
                if (sector != null) requestDelete(player, controller, sector);
            }
            case "set" -> {
                if (args.length != 5) {
                    messages.send(sender, "general.usage.sectors");
                    return;
                }
                handleSet(sender, args, messages, controller);
            }
            default -> messages.send(sender, "general.usage.sectors");
        }
    }

    private void handleSet(CommandSender sender, String[] args, Messages messages,
                           SectorRuntimeController controller) {
        String sector = args[2];
        String threshold = args[3].toLowerCase(Locale.ROOT);
        if (!threshold.equals("capture") && !threshold.equals("held")) {
            messages.send(sender, "sector.set_invalid");
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
                        "sector", sector, "what", threshold, "value", formatTenths(tenths)));
            };
            runOnMain(reply);
        });
    }

    private SectorDefinition findSector(SectorRuntimeController controller, String id,
                                        Messages messages, CommandSender sender) {
        return controller.registry().get(id).orElseGet(() -> {
            messages.send(sender, "sector.not_found", Map.of("sector", id));
            return null;
        });
    }

    private void showInfo(CommandSender sender, SectorRuntimeController controller,
                          SectorDefinition sector) {
        Messages messages = new Messages(configs.messages());
        SectorRuntimeState state = controller.state(sector.id()).orElseThrow();
        SectorStatePolicy policy = controller.policy(sector.id());
        messages.send(sender, "sector.info.header", Map.of("sector", sector.id()));
        messages.send(sender, "sector.info.world", Map.of("world", sector.worldName()));
        messages.send(sender, "sector.info.state", Map.of("state", state.state().name()));
        messages.send(sender, "sector.info.kpm", Map.of(
                "kpm", formatKpm(controller.kpmTracker().kpm(sector.id()))));
        messages.send(sender, "sector.info.capture", Map.of(
                "kpm_capture", formatTenths(policy.captureTenths())));
        messages.send(sender, "sector.info.held", Map.of(
                "kpm_held", formatTenths(policy.effectiveHeldTenths())));
        messages.send(sender, "sector.info.time", Map.of("time", remainingTime(sector, state)));
        messages.send(sender, "sector.info.area", Map.of("area", Long.toString(sector.area())));
        messages.send(sender, "sector.info.stronghold", Map.of("stronghold", "NOT_BUILT"));
    }

    private void teleport(Player player, SectorDefinition sector) {
        Messages messages = new Messages(configs.messages());
        World world = Bukkit.getWorld(sector.worldId());
        GridPoint origin = sector.vertices().getFirst();
        if (world == null || !world.isChunkLoaded(origin.x() >> 4, origin.z() >> 4)) {
            messages.send(player, "sector.tp_failed", Map.of("sector", sector.id()));
            return;
        }
        var top = SurfaceResolver.top(world, origin.x(), origin.z(), world.getMaxHeight() - 1);
        if (top.isEmpty()) {
            messages.send(player, "sector.tp_failed", Map.of("sector", sector.id()));
            return;
        }
        double y = top.getAsDouble() + 1.0;
        int feetY = (int) Math.floor(y);
        if (feetY + 1 >= world.getMaxHeight()
                || !world.getBlockAt(origin.x(), feetY, origin.z()).isPassable()
                || !world.getBlockAt(origin.x(), feetY + 1, origin.z()).isPassable()) {
            messages.send(player, "sector.tp_failed", Map.of("sector", sector.id()));
            return;
        }
        Location target = new Location(world, origin.x() + 0.5, y, origin.z() + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(target);
        messages.send(player, "sector.tp_done", Map.of("sector", sector.id()));
    }

    private void requestDelete(Player player, SectorRuntimeController controller,
                               SectorDefinition sector) {
        int seconds = confirmations.request(player, () -> deleteSector(player, controller, sector.id()));
        new Messages(configs.messages()).send(player, "sector.delete_prompt", Map.of(
                "sector", sector.id(), "seconds", Integer.toString(seconds)));
    }

    private void deleteSector(Player player, SectorRuntimeController controller, String sectorId) {
        Path file = plugin.getDataFolder().toPath().resolve("sectors.yml");
        final String oldText;
        final String updatedText;
        try {
            oldText = Files.readString(file, StandardCharsets.UTF_8);
            updatedText = YamlTextEditor.removeSectorBlock(oldText, sectorId);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not prepare deletion of sector " + sectorId
                    + ": " + exception.getMessage());
            new Messages(configs.messages()).send(player, "sector.delete_failed", Map.of("sector", sectorId));
            return;
        }

        // DB/runtime deletion happens first. If it fails, YAML remains untouched.
        controller.deleteSector(sectorId).whenComplete((ignored, error) -> runOnMain(() -> {
            if (error != null) {
                plugin.getLogger().warning("Could not delete sector " + sectorId + ": " + error.getMessage());
                new Messages(configs.messages()).send(player, "sector.delete_failed",
                        Map.of("sector", sectorId));
                return;
            }
            try {
                writeAtomically(file, updatedText);
                configs.reload(controller.nonEnemySectorIds());
                controller.reloadDefinitions();
                new Messages(configs.messages()).send(player, "sector.delete_done",
                        Map.of("sector", sectorId));
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not update sectors.yml after deleting " + sectorId
                        + ": " + exception.getMessage());
                // Reload the still-existing YAML entry as a fresh ENEMY runtime row.
                try {
                    writeAtomically(file, oldText);
                    configs.reload(controller.nonEnemySectorIds());
                    controller.reloadDefinitions();
                } catch (Exception rollbackError) {
                    plugin.getLogger().severe("Could not recover sector YAML after deletion failure: "
                            + rollbackError.getMessage());
                }
                new Messages(configs.messages()).send(player, "sector.delete_failed",
                        Map.of("sector", sectorId));
            }
        }));
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

    private static String formatTenths(int tenths) {
        return BigDecimal.valueOf(tenths, 1).stripTrailingZeros().toPlainString();
    }

    private static String formatKpm(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String remainingTime(SectorDefinition sector, SectorRuntimeState state) {
        if (sector.infiniteSafe()) return "∞";
        long seconds = state.stateLeftSeconds() != null
                ? state.stateLeftSeconds()
                : state.state() == SectorState.ENEMY ? state.occupationLeftSeconds() : 0L;
        if (seconds <= 0) return "—";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (IllegalStateException exception) {
                plugin.getLogger().warning("Could not schedule admin response on the main thread: "
                        + exception.getMessage());
            }
        }
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "sectors-", ".yml.tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ironfrontiers.admin")) return List.of();

        if (args.length == 1) {
            return filter(List.of("reload", "debug", "sectors", "agree"), args[0]);
        }

        SectorRuntimeController controller = controllerSupplier.get();
        List<String> sectorIds = controller != null && databaseReady.getAsBoolean()
                ? controller.registry().sectors().stream().map(SectorDefinition::id).toList()
                : List.of();

        if (args[0].equalsIgnoreCase("debug") && configs.config().getBoolean("debug", false)) {
            if (args.length == 2) return filter(List.of("kill", "state"), args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("kill") || args[1].equalsIgnoreCase("state"))) {
                return filter(sectorIds, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("state")) {
                return filter(Arrays.stream(SectorState.values()).map(Enum::name).toList(), args[3]);
            }
        }

        if (args[0].equalsIgnoreCase("sectors")) {
            if (args.length == 2) {
                return filter(List.of("list", "info", "tp", "show", "create", "delete", "set"), args[1]);
            }
            if (args.length == 3 && !args[1].equalsIgnoreCase("create")) {
                return filter(sectorIds, args[2]);
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

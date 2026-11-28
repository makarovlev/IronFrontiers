package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.api.SectorState;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.config.ReloadReport;
import com.gachicraft.ironfrontiers.geometry.GridPoint;
import com.gachicraft.ironfrontiers.geometry.IntRect;
import com.gachicraft.ironfrontiers.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
                    Map.of("sector", args[2],
                            "state", messages.raw("sector.states." + state.name().toLowerCase(Locale.ROOT))));
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
                    SectorStatePolicy policy = controller.policy(sector.id());
                    if (state == null || policy == null) continue;
                    Map<String, String> placeholders = Map.of(
                            "sector", sector.id() + " — " + sector.displayName(),
                            "state", messages.raw("sector.states." + state.state().name().toLowerCase(Locale.ROOT)),
                            "kpm", formatKpm(controller.kpmTracker().kpm(sector.id())),
                            "capture", formatTenths(policy.captureTenths()),
                            "held", formatTenths(policy.effectiveHeldTenths()),
                            "players", Integer.toString(playersInside(controller, sector)),
                            "stronghold", configs.sectors().isConfigurationSection(
                                    "sectors." + sector.id() + ".stronghold")
                                    ? messages.raw("sector.stronghold.configured")
                                    : messages.raw("sector.stronghold.not_configured"));
                    Component line = messages.component("sector.line", placeholders)
                            .clickEvent(ClickEvent.runCommand("/if sectors info " + sector.id()));
                    Component teleport = Component.text(" [tp]")
                            .clickEvent(ClickEvent.runCommand("/if sectors tp " + sector.id()));
                    sender.sendMessage(line.append(teleport));
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
                        "sector", sector,
                    "what", current.raw("sector.thresholds." + threshold),
                    "value", formatTenths(tenths)));
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
        messages.send(sender, "sector.info.state", Map.of("state",
                messages.raw("sector.states." + state.state().name().toLowerCase(Locale.ROOT))));
        messages.send(sender, "sector.info.kpm", Map.of(
                "kpm", formatKpm(controller.kpmTracker().kpm(sector.id()))));
        var override = controller.override(sector.id()).orElse(null);
        messages.send(sender, "sector.info.capture", Map.of(
                "kpm_capture", thresholdText(sector.kpmCaptureTenths(), policy.captureTenths())));
        messages.send(sender, "sector.info.capture_source", Map.of(
                "source", override != null && override.captureTenths() != null
                        ? messages.raw("sector.sources.database_override")
                        : valueSource(messages, sector.id(), "kpm_capture")));
        messages.send(sender, "sector.info.held", Map.of(
                "kpm_held", thresholdText(sector.kpmHeldTenths(), policy.effectiveHeldTenths())));
        messages.send(sender, "sector.info.held_source", Map.of(
                "source", override != null && override.heldTenths() != null
                        ? messages.raw("sector.sources.database_override")
                        : valueSource(messages, sector.id(), "kpm_held")));
        messages.send(sender, "sector.info.time", Map.of("time", remainingTime(sector, state)));
        messages.send(sender, "sector.info.area", Map.of("area", Long.toString(sector.area())));
        IntRect bounds = sector.geometry().bounds();
        messages.send(sender, "sector.info.bounds", Map.of("bounds",
                bounds.minX() + "," + bounds.minZ() + " — " + bounds.maxX() + "," + bounds.maxZ()));
        messages.send(sender, "sector.info.vertices", Map.of(
                "vertices", Integer.toString(sector.vertices().size())));
        messages.send(sender, "sector.info.players", Map.of(
                "players", Integer.toString(playersInside(controller, sector))));
        messages.send(sender, "sector.info.anchors", Map.of(
                "anchors", Integer.toString(sector.spawnAnchors().size())));
        messages.send(sender, "sector.info.infinite_safe", Map.of(
                "value", sector.infiniteSafe() ? "да" : "нет"));
        messages.send(sender, "sector.info.safe_time", Map.of(
                "seconds", Integer.toString(sector.safeTimeSeconds()),
                "source", valueSource(messages, sector.id(), "safe_time")));
        messages.send(sender, "sector.info.destruction_time", Map.of(
                "seconds", Integer.toString(sector.destructionTimeSeconds()),
                "source", valueSource(messages, sector.id(), "destruction_time")));
        messages.send(sender, "sector.info.occupation_time", Map.of(
                "seconds", Integer.toString(sector.occupationTimeSeconds()),
                "source", valueSource(messages, sector.id(), "occupation_time")));
        org.bukkit.configuration.ConfigurationSection stronghold =
                configs.sectors().getConfigurationSection("sectors." + sector.id() + ".stronghold");
        if (stronghold == null) {
            messages.send(sender, "sector.info.stronghold",
                    Map.of("stronghold", messages.raw("sector.stronghold.not_configured")));
        } else {
            messages.send(sender, "sector.info.stronghold",
                    Map.of("stronghold", messages.raw("sector.stronghold.not_built")));
            messages.send(sender, "sector.info.stronghold_npc", Map.of(
                    "npc", Integer.toString(stronghold.getInt("npc", -1))));
            messages.send(sender, "sector.info.stronghold_npc_coord", Map.of(
                    "coord", stronghold.getString("npc_coord", "—")));
            messages.send(sender, "sector.info.stronghold_blueprint", Map.of(
                    "blueprint", stronghold.getString("blueprint", "—")));
            messages.send(sender, "sector.info.stronghold_center", Map.of(
                    "center", stronghold.getString("center", "—")));
            org.bukkit.configuration.ConfigurationSection requirements =
                    stronghold.getConfigurationSection("requirements");
            messages.send(sender, "sector.info.stronghold_requirements", Map.of(
                    "count", Integer.toString(requirements == null ? 0 : requirements.getKeys(false).size())));
        }
    }

    private String valueSource(Messages messages, String sectorId, String field) {
        return configs.sectors().contains("sectors." + sectorId + "." + field)
                ? messages.raw("sector.sources.sector")
                : messages.raw("sector.sources.defaults");
    }

    private static String thresholdText(int configuredTenths, int effectiveTenths) {
        String configured = formatTenths(configuredTenths);
        String effective = formatTenths(effectiveTenths);
        return configuredTenths == effectiveTenths
                ? effective : configured + " → " + effective;
    }

    private static int playersInside(SectorRuntimeController controller, SectorDefinition sector) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().getUID().equals(sector.worldId())) continue;
            int x = player.getLocation().getBlockX();
            int z = player.getLocation().getBlockZ();
            SectorDefinition current = controller.registry()
                    .sectorAtDefinition(sector.worldId(), x, z).orElse(null);
            if (current != null && current.id().equalsIgnoreCase(sector.id())) count++;
        }
        return count;
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
            // Commit the config edit first. A disk error must never delete live DB state.
            writeAtomically(file, updatedText);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not prepare deletion of sector " + sectorId
                    + ": " + exception.getMessage());
            new Messages(configs.messages()).send(player, "sector.delete_failed", Map.of("sector", sectorId));
            return;
        }

        // The controller marks the id as deleting immediately and then serializes DB deletion
        // after any pending state writes. Until it completes, the in-memory registry stays intact.
        controller.deleteSector(sectorId).whenComplete((ignored, error) -> runOnMain(() -> {
            if (error != null) {
                plugin.getLogger().warning("Could not delete sector " + sectorId + ": " + error.getMessage());
                try {
                    writeAtomically(file, oldText);
                    configs.reload(controller.nonEnemySectorIds());
                    controller.reloadDefinitions();
                } catch (Exception rollbackError) {
                    plugin.getLogger().severe("Could not restore sectors.yml after failed deletion of "
                            + sectorId + ": " + rollbackError.getMessage());
                }
                new Messages(configs.messages()).send(player, "sector.delete_failed",
                        Map.of("sector", sectorId));
                return;
            }

            try {
                configs.reload(controller.nonEnemySectorIds());
                controller.reloadDefinitions();
            } catch (Exception exception) {
                // YAML and MySQL are already committed. The runtime entry has been removed;
                // a normal /if reload or restart will re-read the committed file.
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Sector " + sectorId + " was deleted from YAML/MySQL, but config refresh failed", exception);
            }
            if (player.isOnline()) {
                new Messages(configs.messages()).send(player, "sector.delete_done",
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

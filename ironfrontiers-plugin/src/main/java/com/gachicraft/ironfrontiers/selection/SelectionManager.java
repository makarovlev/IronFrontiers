package com.gachicraft.ironfrontiers.selection;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.geometry.AxisAlignedPolygon;
import com.gachicraft.ironfrontiers.geometry.GridPoint;
import com.gachicraft.ironfrontiers.geometry.RasterizedSector;
import com.gachicraft.ironfrontiers.message.Messages;
import com.gachicraft.ironfrontiers.sector.SectorDefinition;
import com.gachicraft.ironfrontiers.sector.SectorRegistry;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import com.gachicraft.ironfrontiers.sector.SurfaceResolver;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import com.gachicraft.ironfrontiers.util.YamlTextEditor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.block.Action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Per-admin sector selection sessions and bounded particle previews. */
public final class SelectionManager implements Listener, AutoCloseable {
    private static final Pattern SECTOR_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String PREVIEW_TIMER = "selection-preview-loop";
    private static final long CLICK_DEBOUNCE_NANOS = 150_000_000L;
    private static final int COLUMNS_PER_TICK = 300;

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final CentralTimerScheduler timers;
    private final java.util.function.Supplier<SectorRuntimeController> controller;
    private final NamespacedKey toolKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private boolean closed;

    public SelectionManager(JavaPlugin plugin, ConfigManager configs, CentralTimerScheduler timers,
                            java.util.function.Supplier<SectorRuntimeController> controller) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configs = Objects.requireNonNull(configs);
        this.timers = Objects.requireNonNull(timers);
        this.controller = Objects.requireNonNull(controller);
        this.toolKey = new NamespacedKey(plugin, "selection_tool");
    }

    public boolean begin(Player player, String sectorId) {
        if (closed) return false;
        if (sectorId == null || !SECTOR_ID.matcher(sectorId).matches()) {
            send(player, "sector.name_invalid", Map.of());
            return false;
        }
        SectorRuntimeController runtime = controller.get();
        if (runtime == null) {
            send(player, "general.db_unavailable", Map.of());
            return false;
        }
        if (runtime.registry().contains(sectorId)) {
            send(player, "sector.name_exists", Map.of());
            return false;
        }
        String worldName = player.getWorld().getName();
        if (configs.config().getStringList("war_worlds").stream()
                .noneMatch(name -> name.equalsIgnoreCase(worldName))) {
            send(player, "selection.err_world", Map.of());
            return false;
        }

        Session previous = sessions.remove(player.getUniqueId());
        if (previous != null) removeTool(player, previous.sectorId);
        sessions.put(player.getUniqueId(), new Session(sectorId, player.getWorld().getUID(),
                worldName, System.nanoTime()));
        giveTool(player, sectorId);
        send(player, "selection.tool_given", Map.of());
        schedulePreview();
        return true;
    }

    public boolean show(Player player, SectorDefinition sector) {
        if (closed || sector == null) return false;
        int duration = Math.max(1, configs.config().getInt("selection.show_duration_seconds", 15));
        previews.put(player.getUniqueId(), new Preview(sector, System.nanoTime()
                + duration * 1_000_000_000L));
        send(player, "sector.show_started", Map.of("seconds", Integer.toString(duration)));
        schedulePreview();
        return true;
    }

    public boolean hasSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String sectorId = toolSector(held);
        if (sectorId == null) return;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        event.setCancelled(true);

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.sectorId.equalsIgnoreCase(sectorId)) {
            send(player, "selection.session_lost", Map.of());
            return;
        }
        if (!debounce(session)) return;

        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR)
                && player.isSneaking()) {
            undo(player, session);
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null) addVertex(player, session, block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (toolSector(event.getPlayer().getInventory().getItemInMainHand()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent event) {
        if (toolSector(event.getPlayer().getInventory().getItemInMainHand()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        previews.remove(id);
        timers.cancel(previewTimerKey(id));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Session removed = sessions.remove(id);
        if (removed != null) send(event.getPlayer(), "selection.session_lost", Map.of());
        previews.remove(id);
        timers.cancel(previewTimerKey(id));
    }

    private boolean debounce(Session session) {
        long now = System.nanoTime();
        if (now - session.lastClickNanos < CLICK_DEBOUNCE_NANOS) return false;
        session.lastClickNanos = now;
        return true;
    }

    private void addVertex(Player player, Session session, Block block) {
        if (!block.getWorld().getUID().equals(session.worldId)) {
            send(player, "selection.err_world", Map.of());
            return;
        }

        GridPoint point = new GridPoint(block.getX(), block.getZ());
        if (session.vertices.isEmpty()) {
            session.vertices.add(point);
            session.referenceYs.add(block.getY());
            sendPointAdded(player, session, point);
            schedulePreview();
            return;
        }

        GridPoint first = session.vertices.getFirst();
        if (point.equals(first)) {
            finish(player, session);
            return;
        }

        GridPoint previous = session.vertices.getLast();
        if (point.x() == previous.x() && point.z() == previous.z()) {
            send(player, "selection.err_axis", Map.of(
                    "px", Integer.toString(previous.x()), "pz", Integer.toString(previous.z())));
            return;
        }
        boolean sameX = point.x() == previous.x();
        boolean sameZ = point.z() == previous.z();
        if (sameX == sameZ) {
            send(player, "selection.err_axis", Map.of(
                    "px", Integer.toString(previous.x()), "pz", Integer.toString(previous.z())));
            return;
        }
        if (reverses(previous, session.vertices.get(session.vertices.size() - 2), point)) {
            send(player, "selection.err_reverse", Map.of());
            return;
        }
        if (intersectsOlderEdge(session.vertices, point)) {
            send(player, "selection.err_overlap_edge", Map.of());
            return;
        }

        session.vertices.add(point);
        session.referenceYs.add(block.getY());
        sendPointAdded(player, session, point);
        schedulePreview();
    }

    private void undo(Player player, Session session) {
        if (session.vertices.isEmpty()) {
            send(player, "selection.no_session", Map.of());
            return;
        }
        session.vertices.removeLast();
        session.referenceYs.removeLast();
        send(player, "selection.point_removed", Map.of());
        schedulePreview();
    }

    private void finish(Player player, Session session) {
        List<GridPoint> vertices = List.copyOf(session.vertices);
        RasterizedSector geometry;
        try {
            geometry = AxisAlignedPolygon.rasterize(vertices);
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("180")) send(player, "selection.err_reverse", Map.of());
            else if (message.contains("axis") || message.contains("aligned")) {
                GridPoint first = vertices.getFirst();
                send(player, "selection.err_close_axis", Map.of(
                        "x0", Integer.toString(first.x()), "z0", Integer.toString(first.z())));
            } else send(player, "selection.err_overlap_edge", Map.of());
            return;
        }

        World world = Bukkit.getWorld(session.worldId);
        if (world == null) {
            send(player, "selection.err_world", Map.of());
            return;
        }

        AtomicReference<GridPoint> outsideBorder = new AtomicReference<>();
        geometry.forEachCell((x, z) -> {
            if (outsideBorder.get() != null) return;
            Location sample = new Location(world, x + 0.5, world.getMinHeight(), z + 0.5);
            if (!world.getWorldBorder().isInside(sample)) outsideBorder.set(new GridPoint(x, z));
        });
        if (outsideBorder.get() != null) {
            GridPoint point = outsideBorder.get();
            send(player, "selection.err_border", Map.of(
                    "x", Integer.toString(point.x()), "z", Integer.toString(point.z())));
            return;
        }

        SectorRuntimeController runtime = controller.get();
        if (runtime == null) return;
        AtomicReference<SectorDefinition> overlap = new AtomicReference<>();
        geometry.forEachCell((x, z) -> {
            if (overlap.get() == null) {
                runtime.registry().sectorAtDefinition(world.getUID(), x, z).ifPresent(overlap::set);
            }
        });
        if (overlap.get() != null) {
            send(player, "selection.err_intersects_sector",
                    Map.of("sector", overlap.get().id()));
            return;
        }

        List<GridPoint> normalized = normalize(vertices);
        String oldText = null;
        boolean fileUpdated = false;
        Path file = plugin.getDataFolder().toPath().resolve("sectors.yml");
        try {
            oldText = Files.readString(file, StandardCharsets.UTF_8);
            String newText = YamlTextEditor.appendSectorBlock(oldText, session.sectorId,
                    sectorYaml(session, normalized));
            writeAtomically(file, newText);
            fileUpdated = true;

            configs.reload(runtime.nonEnemySectorIds());
            runtime.reloadDefinitions();
            if (!runtime.registry().contains(session.sectorId)) {
                throw new IllegalStateException("New sector failed validation and was not registered");
            }
        } catch (Exception exception) {
            if (fileUpdated && oldText != null) {
                try {
                    // Restore the exact previous text if parsing/registration failed.
                    writeAtomically(file, oldText);
                    configs.reload(runtime.nonEnemySectorIds());
                    runtime.reloadDefinitions();
                } catch (Exception rollbackError) {
                    plugin.getLogger().severe("Could not roll back failed sector creation " + session.sectorId
                            + ": " + rollbackError.getMessage());
                }
            }
            plugin.getLogger().warning("Could not create sector " + session.sectorId + ": " + exception.getMessage());
            send(player, "selection.creation_failed", Map.of());
            return;
        }

        long area = geometry.area();
        sessions.remove(player.getUniqueId());
        previews.remove(player.getUniqueId());
        removeTool(player, session.sectorId);
        send(player, "selection.created", Map.of(
                "sector", session.sectorId, "area", Long.toString(area)));
    }

    private String sectorYaml(Session session, List<GridPoint> vertices) {
        StringBuilder coordinates = new StringBuilder("[");
        for (int i = 0; i < vertices.size(); i++) {
            if (i > 0) coordinates.append(", ");
            GridPoint point = vertices.get(i);
            coordinates.append('"').append(point.x()).append(',').append(point.z()).append('"');
        }
        coordinates.append(']');
        return "display_name: '&6" + session.sectorId + "'\n"
                + "world: \"" + session.worldName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n"
                + "coords: " + coordinates + "\n"
                + "mobs: {}";
    }

    private static List<GridPoint> normalize(List<GridPoint> source) {
        List<GridPoint> result = new ArrayList<>(source);
        boolean changed;
        do {
            changed = false;
            if (result.size() <= 2) break;
            for (int i = 0; i < result.size(); i++) {
                GridPoint previous = result.get((i - 1 + result.size()) % result.size());
                GridPoint current = result.get(i);
                GridPoint next = result.get((i + 1) % result.size());
                boolean horizontal = previous.z() == current.z() && current.z() == next.z();
                boolean vertical = previous.x() == current.x() && current.x() == next.x();
                if (!horizontal && !vertical) continue;
                int firstDirection = horizontal
                        ? Integer.compare(current.x(), previous.x())
                        : Integer.compare(current.z(), previous.z());
                int secondDirection = horizontal
                        ? Integer.compare(next.x(), current.x())
                        : Integer.compare(next.z(), current.z());
                if (firstDirection == secondDirection) {
                    result.remove(i);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return List.copyOf(result);
    }

    private static boolean reverses(GridPoint previous, GridPoint previousPrevious, GridPoint next) {
        if (previousPrevious == null) return false;
        boolean horizontal = previousPrevious.z() == previous.z()
                && previous.z() == next.z();
        boolean vertical = previousPrevious.x() == previous.x()
                && previous.x() == next.x();
        if (!horizontal && !vertical) return false;
        int before = horizontal
                ? Integer.compare(previous.x(), previousPrevious.x())
                : Integer.compare(previous.z(), previousPrevious.z());
        int after = horizontal
                ? Integer.compare(next.x(), previous.x())
                : Integer.compare(next.z(), previous.z());
        return before != 0 && after != 0 && before != after;
    }

    private static boolean intersectsOlderEdge(List<GridPoint> vertices, GridPoint next) {
        if (vertices.size() < 3) return false;
        GridPoint start = vertices.getLast();
        for (int i = 0; i < vertices.size() - 2; i++) {
            GridPoint a = vertices.get(i);
            GridPoint b = vertices.get(i + 1);
            if (segmentsIntersect(start, next, a, b)) return true;
        }
        return false;
    }

    private static boolean segmentsIntersect(GridPoint a1, GridPoint a2, GridPoint b1, GridPoint b2) {
        boolean aVertical = a1.x() == a2.x();
        boolean bVertical = b1.x() == b2.x();
        if (aVertical && bVertical) {
            return a1.x() == b1.x() && rangesOverlap(a1.z(), a2.z(), b1.z(), b2.z());
        }
        if (!aVertical && !bVertical) {
            return a1.z() == b1.z() && rangesOverlap(a1.x(), a2.x(), b1.x(), b2.x());
        }
        GridPoint verticalStart = aVertical ? a1 : b1;
        GridPoint verticalEnd = aVertical ? a2 : b2;
        GridPoint horizontalStart = aVertical ? b1 : a1;
        GridPoint horizontalEnd = aVertical ? b2 : a2;
        return between(verticalStart.x(), horizontalStart.x(), horizontalEnd.x())
                && between(horizontalStart.z(), verticalStart.z(), verticalEnd.z());
    }

    private static boolean rangesOverlap(int a1, int a2, int b1, int b2) {
        return Math.max(Math.min(a1, a2), Math.min(b1, b2))
                <= Math.min(Math.max(a1, a2), Math.max(b1, b2));
    }

    private static boolean between(int value, int a, int b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }

    private void sendPointAdded(Player player, Session session, GridPoint point) {
        send(player, "selection.point_added", Map.of(
                "n", Integer.toString(session.vertices.size()),
                "x", Integer.toString(point.x()), "z", Integer.toString(point.z())));
    }

    private void giveTool(Player player, String sectorId) {
        String materialName = configs.config().getString("selection.tool_material", "STICK");
        Material material = Material.matchMaterial(materialName == null ? "STICK" : materialName);
        if (material == null || !material.isItem()) material = Material.STICK;
        ItemStack tool = new ItemStack(material);
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(new Messages(configs.messages()).component("selection.tool_name"));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, sectorId);
        tool.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(tool);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void removeTool(Player player, String sectorId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (sectorId.equalsIgnoreCase(toolSector(contents[i]))) contents[i] = null;
        }
        player.getInventory().setContents(contents);
    }

    private String toolSector(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private void schedulePreview() {
        if (closed || (sessions.isEmpty() && previews.isEmpty())) return;
        timers.schedule(PREVIEW_TIMER, "selection", 8L, this::tickPreview);
    }

    private void tickPreview() {
        if (closed) return;
        long now = System.nanoTime();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos <= now
                || Bukkit.getPlayer(entry.getKey()) == null);
        int[] budget = {COLUMNS_PER_TICK};
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                sessions.remove(entry.getKey());
                continue;
            }
            renderSession(player, entry.getValue(), budget);
            if (budget[0] <= 0) break;
        }
        if (budget[0] > 0) {
            for (Map.Entry<UUID, Preview> entry : List.copyOf(previews.entrySet())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) continue;
                renderSector(player, entry.getValue().sector, budget);
                if (budget[0] <= 0) break;
            }
        }
        if (!sessions.isEmpty() || !previews.isEmpty()) schedulePreview();
    }

    private void renderSession(Player player, Session session, int[] budget) {
        World world = Bukkit.getWorld(session.worldId);
        if (world == null || session.vertices.isEmpty()) return;
        if (session.vertices.size() == 1) {
            GridPoint point = session.vertices.getFirst();
            renderColumn(player, world, point.x(), point.z(), session.referenceYs.getFirst(),
                    Color.LIME, budget);
            return;
        }
        for (int i = 0; i < session.vertices.size() - 1 && budget[0] > 0; i++) {
            renderEdge(player, world, session.vertices.get(i), session.vertices.get(i + 1),
                    session.referenceYs.get(i), session.referenceYs.get(i + 1), false, budget);
        }
        if (budget[0] > 0) {
            renderColumn(player, world, session.vertices.getFirst().x(), session.vertices.getFirst().z(),
                    session.referenceYs.getFirst(), Color.LIME, budget);
            GridPoint last = session.vertices.getLast();
            renderColumn(player, world, last.x(), last.z(), session.referenceYs.getLast(), Color.RED, budget);
        }
    }

    private void renderSector(Player player, SectorDefinition sector, int[] budget) {
        World world = Bukkit.getWorld(sector.worldId());
        if (world == null || !player.getWorld().getUID().equals(world.getUID())) return;
        List<GridPoint> vertices = sector.vertices();
        for (int i = 0; i < vertices.size() && budget[0] > 0; i++) {
            GridPoint first = vertices.get(i);
            GridPoint second = vertices.get((i + 1) % vertices.size());
            renderEdge(player, world, first, second, world.getMaxHeight() - 1,
                    world.getMaxHeight() - 1, i == 0, budget);
        }
        if (budget[0] > 0) {
            GridPoint first = vertices.getFirst();
            renderColumn(player, world, first.x(), first.z(), world.getMaxHeight() - 1, Color.LIME, budget);
        }
    }

    private void renderEdge(Player player, World world, GridPoint first, GridPoint second,
                            int firstY, int secondY, boolean highlightStart, int[] budget) {
        int dx = Integer.compare(second.x(), first.x());
        int dz = Integer.compare(second.z(), first.z());
        int length = Math.abs(second.x() - first.x()) + Math.abs(second.z() - first.z());
        double previousTop = Double.NaN;
        int previousX = first.x();
        int previousZ = first.z();
        for (int step = 0; step <= length && budget[0] > 0; step++) {
            int x = first.x() + dx * step;
            int z = first.z() + dz * step;
            if (distanceSquared(player, x + 0.5, z + 0.5)
                    > Math.pow(configs.config().getInt("selection.particle_view_radius", 48), 2)) {
                previousTop = Double.NaN;
                continue;
            }
            OptionalDouble top = SurfaceResolver.top(world, x, z,
                    step == 0 ? firstY : step == length ? secondY : (firstY + secondY) / 2);
            budget[0]--;
            if (top.isEmpty()) {
                previousTop = Double.NaN;
                continue;
            }
            double y = top.getAsDouble() + configs.config().getDouble("selection.ribbon_height_offset", 0.1);
            Color color = step == 0 && highlightStart ? Color.LIME
                    : step == length ? Color.RED : Color.fromRGB(255, 190, 40);
            drawColumn(player, x + 0.5, z + 0.5, y, color);
            if (Double.isFinite(previousTop) && Math.abs(y - previousTop) > 0.05) {
                double boundaryX = (previousX + x) / 2.0 + 0.5;
                double boundaryZ = (previousZ + z) / 2.0 + 0.5;
                double minY = Math.min(y, previousTop);
                double maxY = Math.max(y, previousTop);
                double particleStep = Math.max(0.1,
                        configs.config().getDouble("selection.particle_step", 0.5));
                for (double vertical = minY; vertical <= maxY; vertical += particleStep) {
                    player.spawnParticle(Particle.REDSTONE, boundaryX, vertical, boundaryZ, 1,
                            0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 190, 40), 1.0F));
                }
            }
            previousTop = y;
            previousX = x;
            previousZ = z;
        }
    }

    private void renderColumn(Player player, World world, int x, int z, int referenceY,
                              Color color, int[] budget) {
        if (budget[0] <= 0 || distanceSquared(player, x + 0.5, z + 0.5)
                > Math.pow(configs.config().getInt("selection.particle_view_radius", 48), 2)) return;
        OptionalDouble top = SurfaceResolver.top(world, x, z, referenceY);
        budget[0]--;
        if (top.isPresent()) {
            drawColumn(player, x + 0.5, z + 0.5,
                    top.getAsDouble() + configs.config().getDouble("selection.ribbon_height_offset", 0.1), color);
        }
    }

    private void drawColumn(Player player, double x, double z, double y, Color color) {
        double particleStep = Math.max(0.1, configs.config().getDouble("selection.particle_step", 0.5));
        double maxStep = Math.min(1.0, particleStep);
        int count = Math.max(1, (int) Math.ceil(1.0 / maxStep));
        for (int i = 0; i < count; i++) {
            double offset = (i + 0.5) / count - 0.5;
            player.spawnParticle(Particle.REDSTONE, x + offset, y, z, 1,
                    0, 0, 0, 0, new Particle.DustOptions(color, 1.0F));
        }
    }

    private static double distanceSquared(Player player, double x, double z) {
        double dx = player.getLocation().getX() - x;
        double dz = player.getLocation().getZ() - z;
        return dx * dx + dz * dz;
    }

    private static String previewTimerKey(UUID playerId) {
        return "selection-preview-" + playerId;
    }

    private void send(Player player, String key, Map<String, String> placeholders) {
        new Messages(configs.messages()).send(player, key, placeholders);
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
    public void close() {
        closed = true;
        timers.cancel(PREVIEW_TIMER);
        sessions.clear();
        previews.clear();
    }

    private static final class Session {
        private final String sectorId;
        private final UUID worldId;
        private final String worldName;
        private final List<GridPoint> vertices = new ArrayList<>();
        private final List<Integer> referenceYs = new ArrayList<>();
        private long lastClickNanos;

        private Session(String sectorId, UUID worldId, String worldName, long startedAt) {
            this.sectorId = sectorId;
            this.worldId = worldId;
            this.worldName = worldName;
            this.lastClickNanos = startedAt - CLICK_DEBOUNCE_NANOS;
        }
    }

    private record Preview(SectorDefinition sector, long expiresAtNanos) { }
}

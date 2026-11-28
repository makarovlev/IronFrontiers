package com.gachicraft.ironfrontiers.mobs;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.sector.SectorDefinition;
import com.gachicraft.ironfrontiers.sector.SectorRegistry;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeController;
import com.gachicraft.ironfrontiers.sector.SectorRuntimeState;
import com.gachicraft.ironfrontiers.sector.SpawnAnchor;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import com.gachicraft.privatemobsx.api.DespawnReason;
import com.gachicraft.privatemobsx.api.event.PrivateMobDeathEvent;
import com.gachicraft.privatemobsx.api.event.PrivateMobDespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Supplier;

/**
 * Main-thread spawn session coordinator. All Bukkit and PrivateMobsX calls are
 * performed here on the primary thread; only loaded chunks are queried.
 */
public final class SpawnDirector implements MobSpawn, Listener, AutoCloseable {
    private static final String TRIGGER_TIMER = "spawn-director-trigger";
    private static final String DESPAWN_TIMER = "spawn-director-despawn";
    private static final int EMPTY_RETRY_SECONDS = 5;

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final CentralTimerScheduler timers;
    private final ServicesPrivateMobsBridge mobs;
    private final MobLevelResolver levels;
    private final Supplier<SectorRuntimeController> controllerSupplier;
    private final Random random = new Random();
    private final Map<SessionKey, PlayerSession> sessions = new LinkedHashMap<>();
    private final Set<String> timerKeys = new HashSet<>();
    private final Set<String> warnedMissingClusters = new HashSet<>();

    private MobClusterRegistry clusters;
    private boolean started;
    private boolean closed;
    private int triggerIntervalTicks;
    private int despawnIntervalTicks;
    private int despawnSpreadTicks;

    public SpawnDirector(JavaPlugin plugin, ConfigManager configs, CentralTimerScheduler timers,
                         ServicesPrivateMobsBridge mobs,
                         Supplier<SectorRuntimeController> controllerSupplier) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configs = Objects.requireNonNull(configs);
        this.timers = Objects.requireNonNull(timers);
        this.mobs = Objects.requireNonNull(mobs);
        this.controllerSupplier = Objects.requireNonNull(controllerSupplier);
        this.levels = new MobLevelResolver(plugin);
        reloadConfiguration();
    }

    /** Called only after the sector registry and MySQL state are ready. */
    public void start() {
        requirePrimaryThread();
        if (closed) throw new IllegalStateException("SpawnDirector is closed");
        if (started) return;
        reloadConfiguration();
        if (!mobs.isAvailable()) {
            plugin.getLogger().severe("PrivateMobsX public API service is unavailable; mob spawning is disabled");
            return;
        }

        int removed = mobs.removeAllOrphans();
        plugin.getLogger().info("PrivateMobsX orphan cleanup removed " + removed + " mob(s)");
        started = true;
        scheduleTrigger();
        scheduleDespawnCheck();
        plugin.getLogger().info("SpawnDirector started with " + clusters.size() + " mob cluster(s)");
    }

    @Override
    public void reloadConfiguration() {
        requirePrimaryThread();
        clusters = new MobClusterLoader(plugin.getLogger()).load(configs.mobs());
        triggerIntervalTicks = clamp(configs.config().getInt("spawn.check_interval_ticks", 10), 1, 200);
        despawnIntervalTicks = clamp(configs.config().getInt("spawn.despawn_check_interval_ticks", 20), 1, 1200);
        despawnSpreadTicks = clamp(configs.config().getInt("spawn.despawn_spread_ticks", 8), 5, 10);
        if (started && !closed) {
            cancelTimer(TRIGGER_TIMER);
            cancelTimer(DESPAWN_TIMER);
            scheduleTrigger();
            scheduleDespawnCheck();
        }
    }

    @Override
    public void pauseNewSpawns(String sectorId, int durationSeconds) {
        // The authoritative remaining pause is stored in SectorRuntimeState and
        // persisted by SectorRuntimeController. Trigger and chain checks read it.
    }

    private void scheduleTrigger() {
        schedule(TRIGGER_TIMER, triggerIntervalTicks, () -> {
            checkTriggers();
            if (started && !closed) scheduleTrigger();
        });
    }

    private void scheduleDespawnCheck() {
        schedule(DESPAWN_TIMER, despawnIntervalTicks, () -> {
            checkDespawnDistances();
            if (started && !closed) scheduleDespawnCheck();
        });
    }

    private void checkTriggers() {
        SectorRuntimeController controller = controllerSupplier.get();
        if (!started || closed || controller == null || !mobs.isAvailable()) return;
        SectorRegistry registry = controller.registry();
        int triggerDistance = Math.max(1, configs.config().getInt("spawn.trigger_distance", 15));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isWarWorld(player.getWorld().getName())) continue;
            World world = player.getWorld();
            int x = player.getLocation().getBlockX();
            int z = player.getLocation().getBlockZ();

            for (SectorDefinition sector : registry.sectorsNear(world.getUID(), x, z, triggerDistance)) {
                if (sector.spawnAnchors().isEmpty()) continue;
                SessionKey key = new SessionKey(player.getUniqueId(), normalize(sector.id()));
                PlayerSession session = sessions.computeIfAbsent(key, ignored -> new PlayerSession(sector, key));
                if (session.despawning || !session.armed) continue;
                if (!sameAnchors(session.sector, sector)) {
                    beginDespawn(session, "API");
                    continue;
                }
                session.sector = sector;

                SlotSession nearest = nearestEligibleSlot(session, player, null);
                if (nearest == null) continue;
                if (distance(player.getLocation(), nearest.anchor) > triggerDistance) continue;

                // One initial cluster per visit; the session is re-armed after despawn distance.
                session.armed = false;
                if (!spawnSlot(session, nearest, player)) {
                    session.starved = true;
                    session.starvedSinceNanos = System.nanoTime();
                }
            }
        }
        tryWakeStarvedSessions();
    }

    private void checkDespawnDistances() {
        SectorRuntimeController controller = controllerSupplier.get();
        if (controller == null || closed) return;
        int despawnDistance = Math.max(1, configs.config().getInt("spawn.despawn_distance", 200));

        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (session.despawning) continue;
            if (!controller.registry().contains(session.sector.id())) {
                beginDespawn(session, "API");
                continue;
            }
            Player player = Bukkit.getPlayer(session.key.owner);
            if (player == null) {
                beginDespawn(session, "OWNER_QUIT");
                continue;
            }
            if (!player.getWorld().getUID().equals(session.sector.worldId())) {
                beginDespawn(session, "WORLD_CHANGE");
                continue;
            }
            if (nearestAnchorDistance(player.getLocation(), session.sector) > despawnDistance) {
                beginDespawn(session, "API");
            }
        }
    }

    private void tryWakeStarvedSessions() {
        if (closed) return;
        int despawnDistance = Math.max(1, configs.config().getInt("spawn.despawn_distance", 200));
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (!session.starved || session.despawning) continue;
            Player player = Bukkit.getPlayer(session.key.owner);
            if (player == null || !player.getWorld().getUID().equals(session.sector.worldId())) continue;
            if (nearestAnchorDistance(player.getLocation(), session.sector) > despawnDistance) continue;
            SlotSession next = nearestEligibleSlot(session, player, null);
            if (next != null && spawnSlot(session, next, player)) {
                session.starved = false;
                session.starvedSinceNanos = 0;
            }
        }
    }

    private SlotSession nearestEligibleSlot(PlayerSession session, Player player, String excludedSlot) {
        if (isPaused(session.sector.id())) return null;
        int perSectorLimit = Math.max(1, configs.config().getInt("spawn.max_alive_clusters_per_sector", 3));
        int totalLimit = Math.max(1, configs.config().getInt("spawn.max_alive_clusters_total", 6));
        if (activeClusters(session.key.owner, session.key.sectorId) >= perSectorLimit
                || activeClusters(session.key.owner, null) >= totalLimit) return null;

        double minimumDistance = Math.max(0,
                configs.config().getDouble("spawn.min_spawn_distance", 8));
        long now = System.nanoTime();
        SlotSession best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (SlotSession slot : session.slots.values()) {
            if (slot.state != SlotState.IDLE || slot.retryAfterNanos > now
                    || slot.anchor.slotId().equals(excludedSlot)
                    || clusters.get(slot.anchor.clusterId()).isEmpty()) continue;
            if (!chunkLoaded(session.sector.worldId(), slot.anchor.x(), slot.anchor.z())) continue;
            double distance = distance(player.getLocation(), slot.anchor);
            if (distance < minimumDistance || distance >= bestDistance) continue;
            best = slot;
            bestDistance = distance;
        }
        return best;
    }

    private int activeClusters(UUID owner, String sectorKeyOrNull) {
        int count = 0;
        for (PlayerSession session : sessions.values()) {
            if (!session.key.owner.equals(owner)) continue;
            if (sectorKeyOrNull != null && !session.key.sectorId.equals(sectorKeyOrNull)) continue;
            if (session.despawning) continue;
            for (SlotSession slot : session.slots.values()) {
                if (slot.state == SlotState.ACTIVE && !slot.mobs.isEmpty()) count++;
            }
        }
        return count;
    }

    private boolean spawnSlot(PlayerSession session, SlotSession slot, Player player) {
        if (closed || session.despawning || slot.state == SlotState.ACTIVE
                || slot.state == SlotState.COOLDOWN) return false;
        if (!player.isOnline() || !player.getWorld().getUID().equals(session.sector.worldId())
                || isPaused(session.sector.id())) return false;
        if (!chunkLoaded(session.sector.worldId(), slot.anchor.x(), slot.anchor.z())) return false;

        MobCluster cluster = clusters.get(slot.anchor.clusterId()).orElse(null);
        if (cluster == null) {
            if (warnedMissingClusters.add(slot.anchor.clusterId().toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warning("Sector " + session.sector.id() + " references missing mob cluster "
                        + slot.anchor.clusterId() + "; this spawn anchor is disabled");
            }
            slot.retryAfterNanos = System.nanoTime() + EMPTY_RETRY_SECONDS * 1_000_000_000L;
            return false;
        }

        List<MobEntry> expanded = new ArrayList<>(cluster.totalAmount());
        for (MobEntry entry : cluster.entries()) {
            for (int i = 0; i < entry.amount(); i++) expanded.add(entry);
        }
        Collections.shuffle(expanded, random);
        double spacing = Math.max(0, configs.config().getDouble("spawn.formation.spacing", 2.5));
        double jitter = Math.max(0, configs.config().getDouble("spawn.formation.jitter", 0.3));
        boolean rotate = configs.config().getBoolean("spawn.formation.random_rotation", true);
        double widest = expanded.stream().mapToDouble(this::estimatedMobWidth).max().orElse(0.6);
        List<FormationCalculator.Offset> offsets = FormationCalculator.calculate(
                expanded.size(), spacing, widest, jitter, rotate, random);

        World world = Bukkit.getWorld(session.sector.worldId());
        if (world == null) return false;
        int spawned = 0;
        for (int i = 0; i < expanded.size(); i++) {
            MobEntry entry = expanded.get(i);
            FormationCalculator.Offset offset = offsets.get(i);
            double x = slot.anchor.x() + offset.x();
            double z = slot.anchor.z() + offset.z();
            Location location = findGround(world, x, z, slot.anchor.y());
            if (location == null) continue;

            Integer level = entry.type().name().equals("MYTHICMOBS")
                    ? levels.resolve(entry, player) : null;
            Map<String, String> tags = Map.of(
                    "if_sector", session.sector.id(),
                    "if_slot", slot.anchor.slotId(),
                    "if_cluster", cluster.id(),
                    "if_entry", entry.entryId(),
                    "if_weight", Integer.toString(entry.weightTenths()));
            location.setYaw(random.nextFloat() * 360.0F);
            Optional<UUID> result = mobs.spawn(session.key.owner, entry.type().name(),
                    entry.mobId(), level, location, tags);
            if (result.isPresent()) {
                slot.mobs.put(result.get(), entry.weightTenths());
                spawned++;
            } else if (configs.config().getBoolean("debug", false)) {
                plugin.getLogger().fine("PrivateMobsX rejected " + entry.type() + ":" + entry.mobId()
                        + " at " + location + " for " + session.sector.id() + "/" + slot.anchor.slotId());
            }
        }

        if (spawned == 0) {
            slot.state = SlotState.IDLE;
            slot.retryAfterNanos = System.nanoTime() + EMPTY_RETRY_SECONDS * 1_000_000_000L;
            return false;
        }

        slot.state = SlotState.ACTIVE;
        slot.retryAfterNanos = 0;
        return true;
    }

    private double estimatedMobWidth(MobEntry entry) {
        if (entry.type().name().equals("MYTHICMOBS")) return 0.8;
        try {
            EntityType type = EntityType.valueOf(entry.mobId());
            return switch (type) {
                case GHAST, ENDER_DRAGON -> 4.0;
                case IRON_GOLEM, RAVAGER, HOGLIN, ZOGLIN, POLAR_BEAR -> 1.4;
                case SPIDER, CAVE_SPIDER, SLIME, MAGMA_CUBE -> 1.4;
                case HORSE, DONKEY, MULE, LLAMA, TRADER_LLAMA, CAMEL -> 1.0;
                default -> 0.6;
            };
        } catch (IllegalArgumentException exception) {
            return 0.6;
        }
    }

    private Location findGround(World world, double x, double z, double anchorY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return null;
        int originY = (int) Math.floor(anchorY);
        int searchRange = clamp(configs.config().getInt("spawn.formation.ground_search_range", 6), 0, 32);
        for (int step = 0; step <= searchRange; step++) {
            int[] candidates = step == 0 ? new int[]{originY}
                    : new int[]{originY + step, originY - step};
            for (int feetY : candidates) {
                if (feetY - 1 < world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) continue;
                Block ground = world.getBlockAt(blockX, feetY - 1, blockZ);
                Block feet = world.getBlockAt(blockX, feetY, blockZ);
                Block head = world.getBlockAt(blockX, feetY + 1, blockZ);
                if (ground.isLiquid() || ground.getCollisionShape().getBoundingBoxes().isEmpty()) continue;
                if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
                return new Location(world, x, feetY, z);
            }
        }
        return null;
    }

    private void handleFirstKill(PlayerSession session, SlotSession deadSlot) {
        if (session.chainStarted) return;
        session.chainStarted = true;
        session.nextChainDelaySeconds = Math.max(1,
                configs.config().getInt("spawn.first_kill_timer_seconds", 30));
        queueChainWait(session, deadSlot.anchor.slotId(), session.nextChainDelaySeconds);
    }

    private void queueChainWait(PlayerSession session, String excludedSlot, int delaySeconds) {
        if (session.despawning || hasWaitingSlot(session)) return;
        Player player = Bukkit.getPlayer(session.key.owner);
        if (player == null || !player.getWorld().getUID().equals(session.sector.worldId())) {
            session.pendingChain = true;
            session.pendingChainDelaySeconds = delaySeconds;
            return;
        }
        SlotSession next = nearestEligibleSlot(session, player, excludedSlot);
        if (next == null) {
            session.pendingChain = true;
            session.pendingChainDelaySeconds = delaySeconds;
            session.starved = true;
            session.starvedSinceNanos = System.nanoTime();
            return;
        }
        next.state = SlotState.WAIT;
        session.pendingWaitSlot = next.anchor.slotId();
        session.pendingChain = false;
        session.starved = false;
        session.starvedSinceNanos = 0;
        schedule(sessionTimerKey(session, next, "wait"), delaySeconds * 20L,
                () -> attemptWaitingSlot(session, next));
    }

    private void attemptWaitingSlot(PlayerSession session, SlotSession slot) {
        if (closed || session.despawning || slot.state != SlotState.WAIT) return;
        Player player = Bukkit.getPlayer(session.key.owner);
        if (player == null || !player.getWorld().getUID().equals(session.sector.worldId())) {
            schedule(sessionTimerKey(session, slot, "wait"), 20L,
                    () -> attemptWaitingSlot(session, slot));
            return;
        }

        // Temporarily return WAIT to IDLE so the common eligibility/counting logic can run.
        slot.state = SlotState.IDLE;
        if (!isEligibleForSpawn(session, slot, player)) {
            slot.state = SlotState.WAIT;
            schedule(sessionTimerKey(session, slot, "wait"), 20L,
                    () -> attemptWaitingSlot(session, slot));
            return;
        }
        session.pendingWaitSlot = null;
        if (!spawnSlot(session, slot, player)) {
            slot.state = SlotState.WAIT;
            schedule(sessionTimerKey(session, slot, "wait"), 20L,
                    () -> attemptWaitingSlot(session, slot));
            return;
        }

        session.nextChainDelaySeconds = Math.min(
                Math.max(1, configs.config().getInt("spawn.chain_delay_max_seconds", 300)),
                session.nextChainDelaySeconds
                        + Math.max(1, configs.config().getInt("spawn.chain_delay_step_seconds", 30)));
        queueChainWait(session, slot.anchor.slotId(), session.nextChainDelaySeconds);
    }

    private boolean isEligibleForSpawn(PlayerSession session, SlotSession slot, Player player) {
        if (slot.retryAfterNanos > System.nanoTime() || isPaused(session.sector.id())) return false;
        if (slot.state != SlotState.IDLE || !player.isOnline()
                || !player.getWorld().getUID().equals(session.sector.worldId())) return false;
        if (!chunkLoaded(session.sector.worldId(), slot.anchor.x(), slot.anchor.z())) return false;
        if (distance(player.getLocation(), slot.anchor)
                < Math.max(0, configs.config().getDouble("spawn.min_spawn_distance", 8))) return false;
        return activeClusters(session.key.owner, session.key.sectorId)
                < Math.max(1, configs.config().getInt("spawn.max_alive_clusters_per_sector", 3))
                && activeClusters(session.key.owner, null)
                < Math.max(1, configs.config().getInt("spawn.max_alive_clusters_total", 6));
    }

    private void completeSlot(PlayerSession session, SlotSession slot) {
        if (slot.state != SlotState.ACTIVE || !slot.mobs.isEmpty()) return;
        slot.state = SlotState.COOLDOWN;
        // A pending chain step is reserved first, so the immediate D-spawn below
        // cannot consume the slot that must remain in WAIT.
        if (session.pendingChain && !hasWaitingSlot(session)) {
            queueChainWait(session, slot.anchor.slotId(), session.pendingChainDelaySeconds);
        }

        int respawn = Math.max(0, slot.anchor.respawnSeconds());
        schedule(sessionTimerKey(session, slot, "cooldown"), respawn * 20L, () -> {
            if (session.despawning || slot.state != SlotState.COOLDOWN) return;
            slot.state = SlotState.IDLE;
            if (session.pendingChain && !hasWaitingSlot(session)) {
                queueChainWait(session, slot.anchor.slotId(), session.pendingChainDelaySeconds);
            }
            if (session.starved) {
                tryWakeStarvedSession(session);
            } else {
                Player owner = Bukkit.getPlayer(session.key.owner);
                if (owner != null) {
                    SlotSession next = nearestEligibleSlot(session, owner, null);
                    if (next != null) {
                        if (spawnSlot(session, next, owner)) session.starved = false;
                    } else if (!hasWaitingSlot(session)) {
                        session.starved = true;
                        session.starvedSinceNanos = System.nanoTime();
                    }
                }
            }
        });

        Player owner = Bukkit.getPlayer(session.key.owner);
        if (owner != null) {
            SlotSession next = nearestEligibleSlot(session, owner, null);
            if (next != null && spawnSlot(session, next, owner)) {
                session.starved = false;
                session.starvedSinceNanos = 0;
            } else if (!hasWaitingSlot(session)) {
                session.starved = true;
                session.starvedSinceNanos = System.nanoTime();
            }
        }
    }

    private void tryWakeStarvedSession(PlayerSession session) {
        Player player = Bukkit.getPlayer(session.key.owner);
        if (player == null || session.despawning
                || !player.getWorld().getUID().equals(session.sector.worldId())) return;
        if (nearestAnchorDistance(player.getLocation(), session.sector)
                > Math.max(1, configs.config().getInt("spawn.despawn_distance", 200))) return;
        SlotSession next = nearestEligibleSlot(session, player, null);
        if (next != null && spawnSlot(session, next, player)) session.starved = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrivateMobDeath(PrivateMobDeathEvent event) {
        if (closed || !started) return;
        Map<String, String> tags = event.getTags();
        String sectorId = tags.get("if_sector");
        String slotId = tags.get("if_slot");
        if (sectorId == null || slotId == null) return;

        PlayerSession session = sessions.get(new SessionKey(event.getOwner(), normalize(sectorId)));
        if (session == null || session.despawning) return;
        SlotSession slot = session.slots.get(slotId);
        if (slot == null) return;
        Integer weight = slot.mobs.remove(event.getHandle().getMobId());
        if (weight == null) return;

        Player killer = event.getKiller();
        if (killer != null && killer.getUniqueId().equals(event.getOwner()) && weight > 0) {
            SectorRuntimeController controller = controllerSupplier.get();
            if (controller != null && controller.registry().contains(sectorId)) {
                controller.kpmTracker().recordKill(sectorId, event.getOwner(), weight,
                        controller.currentMonotonicSecond());
            }
        }

        handleFirstKill(session, slot);
        if (slot.mobs.isEmpty()) completeSlot(session, slot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrivateMobDespawn(PrivateMobDespawnEvent event) {
        if (closed || !started || event.getReason() == DespawnReason.DEATH) return;
        Map<String, String> tags = event.getHandle().getTags();
        String sectorId = tags.get("if_sector");
        String slotId = tags.get("if_slot");
        if (sectorId == null || slotId == null) return;

        PlayerSession session = sessions.get(new SessionKey(
                event.getHandle().getOwner(), normalize(sectorId)));
        if (session == null || session.despawning) return;
        SlotSession slot = session.slots.get(slotId);
        if (slot == null) return;
        slot.mobs.remove(event.getHandle().getMobId());
        if (slot.state == SlotState.ACTIVE && slot.mobs.isEmpty()) {
            // Non-death removals must not count KPM or start the kill chain.
            slot.state = SlotState.IDLE;
            slot.retryAfterNanos = System.nanoTime() + EMPTY_RETRY_SECONDS * 1_000_000_000L;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (session.key.owner.equals(event.getPlayer().getUniqueId())) {
                beginDespawn(session, "OWNER_QUIT");
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        UUID newWorld = event.getPlayer().getWorld().getUID();
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (session.key.owner.equals(owner) && !session.sector.worldId().equals(newWorld)) {
                beginDespawn(session, "WORLD_CHANGE");
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (closed || !started) return;
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (!session.starved || session.despawning
                    || !session.sector.worldId().equals(event.getWorld().getUID())) continue;
            boolean containsAnchor = session.slots.values().stream().anyMatch(slot ->
                    (((int) Math.floor(slot.anchor.x())) >> 4) == event.getChunk().getX()
                            && (((int) Math.floor(slot.anchor.z())) >> 4) == event.getChunk().getZ());
            if (containsAnchor) tryWakeStarvedSession(session);
        }
    }

    private void beginDespawn(PlayerSession session, String reason) {
        if (session.despawning) return;
        session.despawning = true;
        cancelSessionTimers(session);
        List<UUID> ids = new ArrayList<>(mobs.getMobIds(session.key.owner, "if_sector", session.sector.id()));
        if (ids.isEmpty()) {
            sessions.remove(session.key);
            return;
        }
        session.despawnQueue = new ArrayDeque<>(ids);
        session.despawnReason = reason;
        session.despawnTicksLeft = despawnSpreadTicks;
        scheduleDespawnBatch(session);
    }

    private void scheduleDespawnBatch(PlayerSession session) {
        String key = session.key.timerPrefix() + ":despawn";
        schedule(key, 1L, () -> {
            if (!session.despawning) return;
            int remaining = session.despawnQueue.size();
            int batchSize = Math.max(1, (int) Math.ceil((double) remaining
                    / Math.max(1, session.despawnTicksLeft)));
            for (int i = 0; i < batchSize && !session.despawnQueue.isEmpty(); i++) {
                mobs.despawn(session.key.owner, session.despawnQueue.removeFirst(), session.despawnReason);
            }
            session.despawnTicksLeft--;
            if (session.despawnQueue.isEmpty() || session.despawnTicksLeft <= 0) {
                sessions.remove(session.key);
            } else {
                scheduleDespawnBatch(session);
            }
        });
    }

    private void cancelSessionTimers(PlayerSession session) {
        for (SlotSession slot : session.slots.values()) {
            cancelTimer(sessionTimerKey(session, slot, "wait"));
            cancelTimer(sessionTimerKey(session, slot, "cooldown"));
        }
    }

    private boolean hasWaitingSlot(PlayerSession session) {
        return session.slots.values().stream().anyMatch(slot -> slot.state == SlotState.WAIT);
    }

    private boolean isPaused(String sectorId) {
        SectorRuntimeController controller = controllerSupplier.get();
        if (controller == null) return true;
        SectorRuntimeState state = controller.state(sectorId).orElse(null);
        return state == null || state.spawnPauseLeftSeconds() > 0;
    }

    private boolean isWarWorld(String worldName) {
        return configs.config().getStringList("war_worlds").stream()
                .anyMatch(name -> name.equalsIgnoreCase(worldName));
    }

    private boolean chunkLoaded(UUID worldId, double x, double z) {
        World world = Bukkit.getWorld(worldId);
        if (world == null) return false;
        return world.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    private static double distance(Location player, SpawnAnchor anchor) {
        double dx = player.getX() - anchor.x();
        double dy = player.getY() - anchor.y();
        double dz = player.getZ() - anchor.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double nearestAnchorDistance(Location location, SectorDefinition sector) {
        double minimum = Double.POSITIVE_INFINITY;
        for (SpawnAnchor anchor : sector.spawnAnchors()) minimum = Math.min(minimum, distance(location, anchor));
        return minimum;
    }

    private static boolean sameAnchors(SectorDefinition a, SectorDefinition b) {
        return a.spawnAnchors().equals(b.spawnAnchors());
    }

    private void schedule(String key, long delayTicks, Runnable action) {
        timerKeys.add(key);
        timers.schedule(key, "spawn-director", Math.max(1L, delayTicks), () -> {
            timerKeys.remove(key);
            if (!closed) action.run();
        });
    }

    private void cancelTimer(String key) {
        timerKeys.remove(key);
        timers.cancel(key);
    }

    private static String sessionTimerKey(PlayerSession session, SlotSession slot, String type) {
        return session.key.timerPrefix() + ":" + slot.anchor.slotId() + ":" + type;
    }

    private void requirePrimaryThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("SpawnDirector must be accessed on the server thread");
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        requirePrimaryThread();
        if (closed) return;
        closed = true;
        started = false;
        for (String key : List.copyOf(timerKeys)) timers.cancel(key);
        timerKeys.clear();
        for (PlayerSession session : List.copyOf(sessions.values())) {
            cancelSessionTimers(session);
            // Shutdown is a hard boundary; do not leave private mobs for the next boot.
            for (UUID mobId : mobs.getMobIds(session.key.owner, "if_sector", session.sector.id())) {
                mobs.despawn(session.key.owner, mobId, "PLUGIN_DISABLE");
            }
        }
        sessions.clear();
    }

    private record SessionKey(UUID owner, String sectorId) {
        String timerPrefix() {
            return "spawn:" + owner + ":" + sectorId;
        }
    }

    private enum SlotState { IDLE, WAIT, ACTIVE, COOLDOWN }

    private static final class SlotSession {
        private final SpawnAnchor anchor;
        private SlotState state = SlotState.IDLE;
        private final Map<UUID, Integer> mobs = new LinkedHashMap<>();
        private long retryAfterNanos;

        private SlotSession(SpawnAnchor anchor) {
            this.anchor = anchor;
        }
    }

    private static final class PlayerSession {
        private final SessionKey key;
        private SectorDefinition sector;
        private final Map<String, SlotSession> slots = new LinkedHashMap<>();
        private boolean armed = true;
        private boolean chainStarted;
        private boolean pendingChain;
        private boolean starved;
        private boolean despawning;
        private int nextChainDelaySeconds;
        private int pendingChainDelaySeconds;
        private String pendingWaitSlot;
        private long starvedSinceNanos;
        private ArrayDeque<UUID> despawnQueue;
        private String despawnReason;
        private int despawnTicksLeft;

        private PlayerSession(SectorDefinition sector, SessionKey key) {
            this.sector = sector;
            this.key = key;
            for (SpawnAnchor anchor : sector.spawnAnchors()) {
                slots.put(anchor.slotId(), new SlotSession(anchor));
            }
        }
    }
}

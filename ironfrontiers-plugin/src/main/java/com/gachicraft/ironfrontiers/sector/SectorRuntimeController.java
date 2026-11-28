package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.api.SectorBounds;
import com.gachicraft.ironfrontiers.api.SectorState;
import com.gachicraft.ironfrontiers.api.StrongholdState;
import com.gachicraft.ironfrontiers.api.SectorView;
import com.gachicraft.ironfrontiers.api.event.*;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.geometry.IntRect;
import com.gachicraft.ironfrontiers.kpm.KpmTracker;
import com.gachicraft.ironfrontiers.mobs.MobSpawn;
import com.gachicraft.ironfrontiers.mobs.NoopMobSpawn;
import com.gachicraft.ironfrontiers.storage.*;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Main-thread runtime coordinator for sector definitions, KPM, state transitions,
 * DB snapshots and Bukkit events. Heavy persistence work is delegated to StateStore.
 */
public final class SectorRuntimeController implements AutoCloseable {
    private static final String TICK_KEY = "sector-automaton-tick";
    private static final String PERSIST_KEY = "sector-state-persist";

    private final Plugin plugin;
    private final Server server;
    private final ConfigManager configs;
    private final StateStore stateStore;
    private final CentralTimerScheduler timers;
    private final Authority authority;
    private final PlayerCountSource playerCount;
    private final MobSpawn mobSpawn;
    private final long monotonicStartNanos = System.nanoTime();
    private final Map<String, SectorRuntimeState> states = new LinkedHashMap<>();
    private final Map<String, SectorStatePolicy> policies = new LinkedHashMap<>();
    private final Map<String, SectorOverride> overrides = new LinkedHashMap<>();
    private final Set<String> deletingSectors = new HashSet<>();

    private SectorRegistry registry;
    private KpmTracker kpmTracker;
    private SectorMessageBatcher messageBatcher;
    private int updateIntervalSeconds;
    private int persistIntervalSeconds;
    private int startupGraceLeftSeconds;
    private boolean started;
    private boolean closed;

    public SectorRuntimeController(Plugin plugin, ConfigManager configs, StateStore stateStore,
                                    CentralTimerScheduler timers, Authority authority,
                                    PlayerCountSource playerCount, MobSpawn mobSpawn) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.configs = Objects.requireNonNull(configs, "configs");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.playerCount = Objects.requireNonNull(playerCount, "playerCount");
        this.mobSpawn = Objects.requireNonNull(mobSpawn, "mobSpawn");
    }

    /** Must be called on the primary thread after both state tables have loaded successfully. */
    public void initialize(SectorRegistry loadedRegistry, List<SectorPersistentState> persisted,
                            List<SectorOverride> persistedOverrides) {
        if (started) throw new IllegalStateException("Sector runtime is already initialized");
        if (!server.isPrimaryThread()) throw new IllegalStateException("Sector runtime initialization must run on the server thread");

        registry = Objects.requireNonNull(loadedRegistry, "loadedRegistry");
        Map<String, SectorPersistentState> rows = new HashMap<>();
        for (SectorPersistentState row : persisted) {
            String key = normalize(row.sectorId());
            if (rows.putIfAbsent(key, row) != null) {
                plugin.getLogger().warning("Duplicate sector_state row ignoring case: " + row.sectorId());
            }
        }

        for (SectorOverride override : persistedOverrides) {
            String key = normalize(override.sectorId());
            if (registry.contains(key)) overrides.put(key, override);
            else plugin.getLogger().warning("Orphan if_sector_override row: " + override.sectorId());
        }

        List<String> sectorIds = registry.sectors().stream().map(SectorDefinition::id).toList();
        updateIntervalSeconds = clamp(configs.config().getInt("kpm.update_interval_seconds", 1), 1, 60);
        persistIntervalSeconds = Math.max(1, configs.config().getInt("state_persist_interval_seconds", 10));
        int windowSeconds = clamp(configs.config().getInt("kpm.window_seconds", 60), 1, 3600);
        int idleCleanup = Math.max(1, configs.config().getInt("kpm.player_idle_cleanup_seconds", 120));
        kpmTracker = new KpmTracker(sectorIds, windowSeconds, idleCleanup);

        for (SectorDefinition definition : registry.sectors()) {
            String key = normalize(definition.id());
            SectorStatePolicy policy = createPolicy(definition, overrides.get(key));
            policies.put(key, policy);

            SectorPersistentState row = rows.remove(key);
            SectorRuntimeState state;
            boolean persistImmediately = false;
            if (row == null) {
                state = SectorRuntimeState.enemy();
                persistImmediately = true;
                plugin.getLogger().info("No DB state for sector " + definition.id() + "; initialized as ENEMY");
            } else {
                state = fromPersistent(row, policy, definition.id());
            }

            if (definition.infiniteSafe()) {
                state = new SectorRuntimeState(SectorState.SAFE, policy.effectiveSafeTimeSeconds(),
                        0, null, 0, state.spawnPauseLeftSeconds(), 0,
                        state.version() + 1, false);
                persistImmediately = true;
            }

            states.put(key, state);
            if (state.spawnPauseLeftSeconds() > 0) {
                mobSpawn.pauseNewSpawns(definition.id(),
                        (int) Math.min(Integer.MAX_VALUE, state.spawnPauseLeftSeconds()));
            }
            if (persistImmediately) persistState(definition, state);
        }

        for (SectorPersistentState orphan : rows.values()) {
            plugin.getLogger().warning("Orphan if_sector_state row without sectors.yml entry: " + orphan.sectorId());
        }

        startupGraceLeftSeconds = Math.max(0,
                configs.config().getInt("sector_defaults.startup_safe_seconds", 300));
        messageBatcher = new SectorMessageBatcher(plugin, server, configs, timers);
        started = true;
        scheduleNextTick();
        schedulePersistence();
        plugin.getLogger().info("Loaded " + registry.size() + " sector(s), KPM window "
                + windowSeconds + "s, startup grace " + startupGraceLeftSeconds + "s");
    }

    public void reloadDefinitions() {
        ensureReady();
        SectorConfigLoader.LoadResult loaded = new SectorConfigLoader(server, plugin.getLogger())
                .load(configs.sectors(), configs.config());
        SectorRegistry nextRegistry = loaded.registry();

        int previousUpdateInterval = updateIntervalSeconds;
        int previousPersistInterval = persistIntervalSeconds;
        int nextUpdateInterval = clamp(configs.config().getInt("kpm.update_interval_seconds", 1), 1, 60);
        int nextWindowSeconds = clamp(configs.config().getInt("kpm.window_seconds", 60), 1, 3600);
        int nextIdleCleanup = Math.max(1,
                configs.config().getInt("kpm.player_idle_cleanup_seconds", 120));
        int nextPersistInterval = Math.max(1,
                configs.config().getInt("state_persist_interval_seconds", 10));
        updateIntervalSeconds = nextUpdateInterval;
        persistIntervalSeconds = nextPersistInterval;
        kpmTracker.reconfigure(nextWindowSeconds, nextIdleCleanup);
        if (previousUpdateInterval != nextUpdateInterval) {
            timers.cancel(TICK_KEY);
        }
        if (previousPersistInterval != nextPersistInterval) {
            timers.cancel(PERSIST_KEY);
        }

        for (SectorDefinition definition : nextRegistry.sectors()) {
            String key = normalize(definition.id());
            if (kpmTracker != null) kpmTracker.ensureSector(definition.id());
            SectorStatePolicy previousPolicy = policies.get(key);
            SectorStatePolicy nextPolicy = createPolicy(definition, overrides.get(key));
            policies.put(key, nextPolicy);

            SectorRuntimeState current = states.get(key);
            if (current == null) {
                current = SectorRuntimeState.enemy();
                states.put(key, current);
                persistState(definition, current);
            } else if (previousPolicy != null && previousPolicy.infiniteSafe() && !nextPolicy.infiniteSafe()
                    && current.state() == SectorState.SAFE) {
                current = new SectorRuntimeState(SectorState.SAFE, nextPolicy.effectiveSafeTimeSeconds(),
                        0, null, 0, current.spawnPauseLeftSeconds(), 0,
                        current.version() + 1, false);
                states.put(key, current);
                persistState(definition, current);
            } else if (nextPolicy.infiniteSafe()) {
                current = new SectorRuntimeState(SectorState.SAFE, nextPolicy.effectiveSafeTimeSeconds(),
                        0, null, 0, current.spawnPauseLeftSeconds(), 0,
                        current.version() + 1, false);
                states.put(key, current);
                persistState(definition, current);
            }
        }

        for (SectorDefinition old : registry.sectors()) {
            if (!nextRegistry.contains(old.id())) {
                plugin.getLogger().warning("Sector " + old.id()
                        + " is absent from the reloaded config but still has runtime/DB state; it will be treated as an orphan until restart.");
            }
        }
        registry = nextRegistry;
        long nowSecond = (System.nanoTime() - monotonicStartNanos) / 1_000_000_000L;
        kpmTracker.tick(nowSecond);
        if (previousUpdateInterval != nextUpdateInterval) {
            scheduleNextTick();
        }
        if (previousPersistInterval != nextPersistInterval) {
            schedulePersistence();
        }
    }

    /** Sector ids whose geometry/other restart-only values must remain unchanged on /if reload. */
    public Set<String> nonEnemySectorIds() {
        ensureReady();
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, SectorRuntimeState> entry : states.entrySet()) {
            if (entry.getValue().state() != SectorState.ENEMY) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    public void tickNow() {
        ensureReady();
        tick();
    }

    private void tick() {
        if (closed || !started) return;

        int dt = updateIntervalSeconds;
        long nowSecond = (System.nanoTime() - monotonicStartNanos) / 1_000_000_000L;
        kpmTracker.tick(nowSecond);

        boolean startupFrozen = startupGraceLeftSeconds > 0;
        if (startupFrozen) startupGraceLeftSeconds = (int) Math.max(0L, startupGraceLeftSeconds - dt);

        if (authority.isLeader()) {
            int online = playerCount.onlinePlayers();
            for (SectorDefinition definition : registry.sectors()) {
                String key = normalize(definition.id());
                if (deletingSectors.contains(key)) continue;
                SectorRuntimeState current = states.get(key);
                if (current == null) continue;
                SectorStatePolicy policy = policies.get(key);
                int kpmTenths = (int) Math.round(kpmTracker.kpm(definition.id()) * 10.0);
                SectorStateMachine.StepResult result = SectorStateMachine.tick(
                        current, policy, kpmTenths, dt, startupFrozen, online);
                if (result.transitioned()) {
                    processTransition(definition, result.transition());
                } else if (!result.state().equals(current)) {
                    states.put(key, result.state());
                }
            }
        }

        scheduleNextTick();
    }

    private void scheduleNextTick() {
        if (closed || !started) return;
        timers.schedule(TICK_KEY, "sector-runtime", updateIntervalSeconds * 20L, this::tick);
    }

    /** Persistence cadence is independent from KPM/state-machine tick cadence. */
    private void schedulePersistence() {
        if (closed || !started) return;
        timers.schedule(PERSIST_KEY, "sector-persistence", persistIntervalSeconds * 20L, () -> {
            if (closed || !started) return;
            persistAll().whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "Periodic sector-state persistence failed", error);
                }
            });
            schedulePersistence();
        });
    }

    private void processTransition(SectorDefinition definition, SectorTransition transition) {
        String key = normalize(definition.id());
        SectorRuntimeState previous = transition.previous();
        SectorRuntimeState next = transition.current();

        // State is changed in memory and the async DB write is enqueued before Bukkit events.
        states.put(key, next);
        CompletableFuture<Void> write = persistState(definition, next);
        write.exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not persist state for sector " + definition.id(), error);
            return null;
        });

        if (transition.kind() == SectorTransition.Kind.CAPTURED) {
            SectorCapturedEvent captured = new SectorCapturedEvent(createView(definition, next));
            server.getPluginManager().callEvent(captured);
            if (captured.isCancelled()) {
                states.put(key, previous);
                persistState(definition, previous);
                return;
            }
        }

        server.getPluginManager().callEvent(new SectorStateChangeEvent(
                createView(definition, next), previous.state(), next.state(), transition.kind().name()));

        switch (transition.kind()) {
            case CAPTURED -> {
                mobSpawn.pauseNewSpawns(definition.id(), Math.toIntExact(next.spawnPauseLeftSeconds()));
                messageBatcher.captured(definition);
            }
            case SAFE_ENDED_TO_HELD -> {
                if (previous.state() == SectorState.SAFE) {
                    server.getPluginManager().callEvent(new SectorSafeEndedEvent(createView(definition, next)));
                }
            }
            case DESTRUCTION_STARTED -> {
                if (previous.state() == SectorState.SAFE) {
                    server.getPluginManager().callEvent(new SectorSafeEndedEvent(createView(definition, next)));
                }
                server.getPluginManager().callEvent(new SectorDestructionStartedEvent(createView(definition, next)));
                messageBatcher.destructionStarted(definition, next.stateLeftSeconds());
            }
            case DESTRUCTION_CANCELLED -> {
                server.getPluginManager().callEvent(new SectorDestructionCancelledEvent(createView(definition, next)));
                messageBatcher.destructionCancelled(definition);
            }
            case LOST -> {
                server.getPluginManager().callEvent(new SectorLostEvent(createView(definition, next)));
                messageBatcher.sectorLost(definition);
            }
        }
    }

    private CompletableFuture<Void> persistState(SectorDefinition definition, SectorRuntimeState state) {
        return stateStore.save(new SectorPersistentState(definition.id(), state.state(),
                state.stateLeftSeconds(), state.occupationLeftSeconds(),
                state.destructionSavedSeconds(), state.resumeWindowLeftSeconds(),
                state.spawnPauseLeftSeconds(), state.version(), 0));
    }

    public CompletableFuture<Void> persistAll() {
        if (!started || registry == null) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (SectorDefinition definition : registry.sectors()) {
            String key = normalize(definition.id());
            if (deletingSectors.contains(key)) continue;
            SectorRuntimeState state = states.get(key);
            if (state != null) writes.add(persistState(definition, state));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    public boolean debugKill(String sectorId, int count, int weightTenths) {
        ensureReady();
        if (count < 1 || count > 10_000 || weightTenths < 1) return false;
        if (deletingSectors.contains(normalize(sectorId)) || !registry.contains(sectorId)) return false;
        long now = (System.nanoTime() - monotonicStartNanos) / 1_000_000_000L;
        for (int i = 0; i < count; i++) {
            kpmTracker.recordDebugKill(sectorId, weightTenths, now);
        }
        return true;
    }

    public boolean debugSetState(String sectorId, SectorState target) {
        ensureReady();
        if (deletingSectors.contains(normalize(sectorId))) return false;
        Optional<SectorDefinition> optional = registry.get(sectorId);
        if (optional.isEmpty()) return false;
        SectorDefinition definition = optional.get();
        String key = normalize(sectorId);
        SectorRuntimeState current = states.get(key);
        if (current.state() == target) return true;
        SectorStatePolicy policy = policies.get(key);
        SectorRuntimeState next = switch (target) {
            case ENEMY -> new SectorRuntimeState(SectorState.ENEMY, null,
                    0, null, 0, 0, 0, current.version() + 1, false);
            case SAFE -> new SectorRuntimeState(SectorState.SAFE, policy.effectiveSafeTimeSeconds(),
                    0, null, 0, policy.spawnPauseAfterCaptureSeconds(), 0,
                    current.version() + 1, false);
            case HELD -> new SectorRuntimeState(SectorState.HELD, null,
                    0, null, 0, current.spawnPauseLeftSeconds(), 0,
                    current.version() + 1, false);
            case DESTRUCTION -> new SectorRuntimeState(SectorState.DESTRUCTION,
                    policy.effectiveDestructionTimeSeconds(), 0, null, 0,
                    current.spawnPauseLeftSeconds(), 0, current.version() + 1, false);
        };
        SectorTransition.Kind kind = switch (target) {
            case ENEMY -> SectorTransition.Kind.LOST;
            case SAFE -> SectorTransition.Kind.CAPTURED;
            case HELD -> SectorTransition.Kind.SAFE_ENDED_TO_HELD;
            case DESTRUCTION -> SectorTransition.Kind.DESTRUCTION_STARTED;
        };
        processTransition(definition, new SectorTransition(kind, current, next));
        return true;
    }

    /**
     * Deletes a sector from MySQL and removes it from the in-memory registry.
     * Call on the primary thread after its YAML block has been removed.
     */
    public CompletableFuture<Void> deleteSector(String sectorId) {
        ensureReady();
        if (!server.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Sector deletion must be initiated on the server thread"));
        }
        SectorDefinition definition = registry.get(sectorId).orElse(null);
        if (definition == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown sector: " + sectorId));
        }
        String key = normalize(definition.id());
        if (!deletingSectors.add(key)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Sector deletion is already in progress: " + definition.id()));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        stateStore.deleteSector(definition.id()).whenComplete((ignored, error) -> {
            Runnable finish = () -> {
                if (error != null) {
                    deletingSectors.remove(key);
                    result.completeExceptionally(error);
                    return;
                }
                try {
                    List<SectorDefinition> remaining = registry.sectors().stream()
                            .filter(existing -> !normalize(existing.id()).equals(key))
                            .toList();
                    com.gachicraft.ironfrontiers.geometry.SectorGeometryIndex.Builder index =
                            new com.gachicraft.ironfrontiers.geometry.SectorGeometryIndex.Builder();
                    for (SectorDefinition existing : remaining) {
                        index.add(existing.worldId(), existing.geometry());
                    }
                    SectorRegistry nextRegistry = new SectorRegistry(remaining, index.build());

                    // Publish the new registry only after its full geometry index is built.
                    states.remove(key);
                    policies.remove(key);
                    overrides.remove(key);
                    kpmTracker.removeSector(definition.id());
                    registry = nextRegistry;
                    deletingSectors.remove(key);
                    result.complete(null);
                    plugin.getLogger().info("Deleted sector " + definition.id()
                            + " from runtime and MySQL");
                } catch (RuntimeException exception) {
                    deletingSectors.remove(key);
                    result.completeExceptionally(exception);
                }
            };
            if (server.isPrimaryThread()) finish.run();
            else {
                try {
                    server.getScheduler().runTask(plugin, finish);
                } catch (RuntimeException exception) {
                    deletingSectors.remove(key);
                    result.completeExceptionally(exception);
                }
            }
        });
        return result;
    }

    public CompletableFuture<Void> setThresholdOverride(String sectorId, String threshold, int valueTenths) {
        ensureReady();
        if (valueTenths <= 0) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Threshold must be positive"));
        SectorDefinition definition = registry.get(sectorId).orElseThrow(
                () -> new IllegalArgumentException("Unknown sector: " + sectorId));
        String key = normalize(definition.id());
        if (deletingSectors.contains(key)) return CompletableFuture.failedFuture(
                new IllegalStateException("Sector is being deleted: " + definition.id()));
        SectorOverride previous = overrides.get(key);
        Integer capture = previous == null ? null : previous.captureTenths();
        Integer held = previous == null ? null : previous.heldTenths();
        if ("capture".equalsIgnoreCase(threshold)) capture = valueTenths;
        else if ("held".equalsIgnoreCase(threshold)) held = valueTenths;
        else return CompletableFuture.failedFuture(new IllegalArgumentException("Expected capture or held"));

        SectorOverride next = new SectorOverride(definition.id(), capture, held);
        CompletableFuture<Void> result = new CompletableFuture<>();
        stateStore.saveOverride(next).whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                server.getScheduler().runTask(plugin, () -> {
                    if (closed) {
                        result.completeExceptionally(new IllegalStateException("Sector runtime is closed"));
                        return;
                    }
                    overrides.put(key, next);
                    policies.put(key, createPolicy(definition, next));
                    result.complete(null);
                });
            } catch (IllegalStateException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    public SectorRegistry registry() {
        ensureReady();
        return registry;
    }

    public Optional<SectorRuntimeState> state(String sectorId) {
        ensureReady();
        return Optional.ofNullable(states.get(normalize(sectorId)));
    }

    public SectorStatePolicy policy(String sectorId) {
        ensureReady();
        return policies.get(normalize(sectorId));
    }

    public Optional<SectorOverride> override(String sectorId) {
        ensureReady();
        return Optional.ofNullable(overrides.get(normalize(sectorId)));
    }

    public KpmTracker kpmTracker() {
        ensureReady();
        return kpmTracker;
    }

    public int startupGraceLeftSeconds() {
        return startupGraceLeftSeconds;
    }

    private SectorStatePolicy createPolicy(SectorDefinition definition, SectorOverride override) {
        var config = configs.config();
        int capture = override != null && override.captureTenths() != null
                ? override.captureTenths() : definition.kpmCaptureTenths();
        int held = override != null && override.heldTenths() != null
                ? override.heldTenths() : definition.kpmHeldTenths();
        return new SectorStatePolicy(capture, held,
                definition.safeTimeSeconds(), definition.destructionTimeSeconds(),
                definition.occupationTimeSeconds(),
                Math.max(1, config.getInt("sector_defaults.destruction_cancel_hold_seconds", 10)),
                Math.max(0, config.getInt("sector_defaults.destruction_resume_window_seconds", 120)),
                Math.max(0, config.getInt("sector_defaults.spawn_pause_after_capture_seconds", 180)),
                definition.infiniteSafe(),
                config.getBoolean("sector_defaults.freeze_when_empty", false),
                false,
                positiveMultiplier(config.getDouble("stronghold.safe_time_multiplier", 2.0), 2.0),
                positiveMultiplier(config.getDouble("stronghold.destruction_time_multiplier", 5.0), 5.0),
                positiveMultiplier(config.getDouble("stronghold.kpm_held_multiplier", 0.9), 0.9));
    }

    private SectorRuntimeState fromPersistent(SectorPersistentState row,
                                               SectorStatePolicy policy, String sectorId) {
        Long stateLeft = row.stateLeftSeconds();
        if ((row.state() == SectorState.SAFE || row.state() == SectorState.DESTRUCTION)
                && stateLeft == null) {
            plugin.getLogger().warning("if_sector_state for " + sectorId
                    + " has no timer in state " + row.state() + "; using the full configured timer");
            stateLeft = row.state() == SectorState.SAFE
                    ? policy.effectiveSafeTimeSeconds() : policy.effectiveDestructionTimeSeconds();
        }
        if ((row.state() == SectorState.ENEMY || row.state() == SectorState.HELD) && stateLeft != null) {
            plugin.getLogger().warning("if_sector_state for " + sectorId
                    + " has an unexpected state timer; the timer is ignored");
            stateLeft = null;
        }
        return new SectorRuntimeState(row.state(), stateLeft, row.occupationLeftSeconds(),
                row.destructionSavedSeconds(), row.resumeWindowLeftSeconds(),
                row.spawnPauseLeftSeconds(), 0, row.version(), false);
    }

    private SectorView createView(SectorDefinition definition, SectorRuntimeState state) {
        IntRect bounds = definition.geometry().bounds();
        SectorStatePolicy policy = policies.get(normalize(definition.id()));
        double kpm = kpmTracker.kpm(definition.id());
        long timer = definition.infiniteSafe() ? -1
                : state.stateLeftSeconds() != null ? state.stateLeftSeconds()
                : state.state() == SectorState.ENEMY ? state.occupationLeftSeconds() : 0;
        return new SectorView(definition.id(), definition.displayName(), definition.worldName(),
                definition.worldId(), state.state(), kpm,
                policy.captureTenths() / 10.0, policy.effectiveHeldTenths() / 10.0,
                timer, definition.geometry().area(),
                new SectorBounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ()),
                StrongholdState.NOT_BUILT);
    }

    private static double positiveMultiplier(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private void ensureReady() {
        if (!started || closed || registry == null || kpmTracker == null) {
            throw new IllegalStateException("Sector runtime is not ready");
        }
    }

    @Override
    public void close() {
        closed = true;
        timers.cancel(TICK_KEY);
        timers.cancel(PERSIST_KEY);
        if (messageBatcher != null) messageBatcher.close();
    }

    /** Exposed pure conversion for tests and future state replication. */
    public static SectorPersistentState toPersistent(String sectorId, SectorRuntimeState state) {
        return new SectorPersistentState(sectorId, state.state(), state.stateLeftSeconds(),
                state.occupationLeftSeconds(), state.destructionSavedSeconds(),
                state.resumeWindowLeftSeconds(), state.spawnPauseLeftSeconds(), state.version(), 0);
    }

}

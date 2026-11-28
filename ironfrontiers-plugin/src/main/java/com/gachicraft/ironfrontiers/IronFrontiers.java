package com.gachicraft.ironfrontiers;

import com.gachicraft.ironfrontiers.bootstrap.ExecutorPools;
import com.gachicraft.ironfrontiers.bootstrap.ServiceRegistry;
import com.gachicraft.ironfrontiers.command.CommandRegistrar;
import com.gachicraft.ironfrontiers.command.ConfirmationManager;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.integration.IntegrationRegistry;
import com.gachicraft.ironfrontiers.sector.*;
import com.gachicraft.ironfrontiers.storage.*;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import com.gachicraft.ironfrontiers.mobs.PrivateMobsBridge;
import com.gachicraft.ironfrontiers.mobs.ServicesPrivateMobsBridge;
import com.gachicraft.ironfrontiers.mobs.SpawnDirector;
import com.gachicraft.ironfrontiers.selection.SelectionManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Plugin lifecycle and explicit dependency bootstrap. */
public final class IronFrontiers extends JavaPlugin {
    private final ServiceRegistry services = new ServiceRegistry();
    private ConfigManager configs;
    private ExecutorPools executors;
    private CentralTimerScheduler timers;
    private DatabaseHandle database;
    private SectorRuntimeController sectorController;
    private SelectionManager selectionManager;
    private ConfirmationManager confirmationManager;
    private ServicesPrivateMobsBridge privateMobsBridge;
    private SpawnDirector spawnDirector;
    private boolean databaseReady;
    private boolean databaseInitializationInProgress;
    private boolean initialDatabaseAttempt = true;
    private int nextDatabaseRetrySeconds = 5;
    private long lastDatabaseWarningNanos;

    @Override
    public void onEnable() {
        try {
            configs = new ConfigManager(this);
            configs.loadInitial();
            services.register(ConfigManager.class, configs);

            executors = new ExecutorPools(
                    configs.config().getInt("database.pool_size", 6),
                    configs.config().getInt("transform.scan_threads", 2),
                    configs.config().getBoolean("multiserver.enabled", false));
            services.register(ExecutorPools.class, executors);

            timers = new CentralTimerScheduler(this);
            timers.start();
            services.register(CentralTimerScheduler.class, timers);

            selectionManager = new SelectionManager(this, configs, timers, () -> sectorController);
            confirmationManager = new ConfirmationManager(this, configs, timers);
            privateMobsBridge = new ServicesPrivateMobsBridge(this);
            spawnDirector = new SpawnDirector(this, configs, timers, privateMobsBridge, () -> sectorController);
            services.register(PrivateMobsBridge.class, privateMobsBridge);
            services.register(SpawnDirector.class, spawnDirector);
            getServer().getPluginManager().registerEvents(selectionManager, this);
            getServer().getPluginManager().registerEvents(confirmationManager, this);
            getServer().getPluginManager().registerEvents(spawnDirector, this);

            Authority authority = new AlwaysLeader();
            PlayerCountSource playerCount = new LocalPlayerCountSource(getServer());
            StateBroadcaster broadcaster = new NoopBroadcaster();
            services.register(Authority.class, authority);
            services.register(StateBroadcaster.class, broadcaster);
            services.register(PlayerCountSource.class, playerCount);

            IntegrationRegistry integrations = new IntegrationRegistry(getServer().getPluginManager());
            integrations.logAvailability(getLogger());
            services.register(IntegrationRegistry.class, integrations);

            if (!privateMobsBridge.isAvailable()) {
                throw new IllegalStateException("PrivateMobsX is enabled but did not register its public API service");
            }

            // Register early so /if reload and the database-unavailable message work
            // even when MySQL is not reachable during startup.
            CommandRegistrar.register(this, configs, () -> sectorController, () -> databaseReady,
                    selectionManager, confirmationManager);
            startDatabaseInitialization();
            getLogger().info("Iron Frontiers bootstrap started (Java " + Runtime.version().feature() + ")");
        } catch (Exception exception) {
            getLogger().severe("Iron Frontiers could not complete bootstrap: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startDatabaseInitialization() {
        if (databaseInitializationInProgress || databaseReady || !isEnabled()) return;
        databaseInitializationInProgress = true;
        DatabaseBootstrapper bootstrapper = new DatabaseBootstrapper(this, executors.database());
        bootstrapper.initialize(configs.config()).whenComplete((handle, error) -> {
            if (!isEnabled()) {
                if (handle != null) handle.close();
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    if (handle != null) handle.close();
                    return;
                }
                databaseInitializationInProgress = false;
                if (error != null) {
                    reportDatabaseFailure(error);
                    scheduleDatabaseRetry();
                    return;
                }

                CompletableFuture<List<SectorPersistentState>> states = handle.stateStore().loadAll();
                CompletableFuture<List<SectorOverride>> overrides = handle.stateStore().loadOverrides();
                states.thenCombine(overrides, LoadedDatabase::new).whenComplete((loaded, loadError) -> {
                    if (!isEnabled()) {
                        handle.close();
                        return;
                    }
                    getServer().getScheduler().runTask(this, () -> {
                        if (!isEnabled()) {
                            handle.close();
                            return;
                        }
                        if (loadError != null) {
                            handle.close();
                            reportDatabaseFailure(loadError);
                            scheduleDatabaseRetry();
                            return;
                        }

                        try {
                            StateStore reliableStore = new RetryingStateStore(this, handle.stateStore(), timers);
                            SectorConfigLoader.LoadResult definitions =
                                    new SectorConfigLoader(getServer(), getLogger())
                                            .load(configs.sectors(), configs.config());
                            SectorRuntimeController controller = new SectorRuntimeController(
                                    this, configs, reliableStore, timers,
                                    services.require(Authority.class),
                                    services.require(PlayerCountSource.class),
                                    spawnDirector);
                            controller.initialize(definitions.registry(), loaded.states(), loaded.overrides());

                            database = new DatabaseHandle(handle.dataSource(), reliableStore);
                            sectorController = controller;
                            services.register(StateStore.class, reliableStore);
                            services.register(SectorRegistry.class, controller.registry());
                            services.register(SectorRuntimeController.class, controller);
                            services.register(com.gachicraft.ironfrontiers.kpm.KpmTracker.class,
                                    controller.kpmTracker());
                            services.register(com.gachicraft.ironfrontiers.sector.KpmSource.class,
                                    controller.kpmTracker());

                            databaseReady = true;
                            spawnDirector.start();
                            nextDatabaseRetrySeconds = 5;
                            getLogger().info("MySQL connected; loaded " + loaded.states().size()
                                    + " persisted sector state(s), " + loaded.overrides().size() + " KPM override(s)");
                        } catch (Exception exception) {
                            handle.close();
                            getLogger().log(java.util.logging.Level.SEVERE,
                                    "Could not initialize sector runtime; mechanics remain disabled", exception);
                            reportDatabaseFailure(exception);
                            scheduleDatabaseRetry();
                        }
                    });
                });
            });
        });
    }

    private void reportDatabaseFailure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        long now = System.nanoTime();
        if (initialDatabaseAttempt || now - lastDatabaseWarningNanos >= 60_000_000_000L) {
            if (initialDatabaseAttempt) {
                getLogger().severe("MySQL is unavailable; sector mechanics will not start: " + cause.getMessage());
            } else {
                getLogger().warning("MySQL is still unavailable; database bootstrap will be retried: " + cause.getMessage());
            }
            lastDatabaseWarningNanos = now;
        }
        initialDatabaseAttempt = false;
    }

    private void scheduleDatabaseRetry() {
        int delay = nextDatabaseRetrySeconds;
        nextDatabaseRetrySeconds = Math.min(nextDatabaseRetrySeconds * 2, 300);
        timers.schedule("database-retry", "infrastructure", delay * 20L, this::startDatabaseInitialization);
    }

    public boolean isDatabaseReady() {
        return databaseReady;
    }

    public SectorRuntimeController sectorController() {
        return sectorController;
    }

    @Override
    public void onDisable() {
        if (spawnDirector != null) spawnDirector.close();
        if (selectionManager != null) selectionManager.close();
        if (confirmationManager != null) confirmationManager.close();
        CompletableFuture<Void> finalFlush = null;
        if (sectorController != null) {
            sectorController.close();
            // Keep the database pool and executor alive until the final state snapshots finish.
            finalFlush = sectorController.persistAll();
        }

        if (finalFlush != null) {
            try {
                finalFlush.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                getLogger().severe("Timed out while flushing sector states during shutdown; some latest states may not have reached MySQL.");
            } catch (Exception exception) {
                getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not flush all sector states during shutdown", exception);
            }
        }

        if (timers != null) timers.close();
        if (database != null) {
            database.close();
            database = null;
        }
        if (executors != null) {
            executors.close();
            executors = null;
        }
        services.clear();
        getLogger().info("Iron Frontiers disabled");
    }

    private record LoadedDatabase(List<SectorPersistentState> states,
                                  List<SectorOverride> overrides) {
        private LoadedDatabase {
            states = List.copyOf(states);
            overrides = List.copyOf(overrides);
        }
    }
}

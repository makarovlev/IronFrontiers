package com.gachicraft.ironfrontiers;

import com.gachicraft.ironfrontiers.bootstrap.ExecutorPools;
import com.gachicraft.ironfrontiers.bootstrap.ServiceRegistry;
import com.gachicraft.ironfrontiers.command.CommandRegistrar;
import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.integration.IntegrationRegistry;
import com.gachicraft.ironfrontiers.sector.*;
import com.gachicraft.ironfrontiers.storage.*;
import com.gachicraft.ironfrontiers.util.CentralTimerScheduler;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletionException;

/** Plugin lifecycle and manual dependency bootstrap. */
public final class IronFrontiers extends JavaPlugin {
    private final ServiceRegistry services = new ServiceRegistry();
    private ConfigManager configs;
    private ExecutorPools executors;
    private CentralTimerScheduler timers;
    private DatabaseHandle database;
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

            services.register(Authority.class, new AlwaysLeader());
            services.register(KpmSource.class, new LocalKpmSource());
            services.register(StateBroadcaster.class, new NoopBroadcaster());
            services.register(PlayerCountSource.class, new LocalPlayerCountSource(getServer()));

            IntegrationRegistry integrations = new IntegrationRegistry(getServer().getPluginManager());
            integrations.logAvailability(getLogger());
            services.register(IntegrationRegistry.class, integrations);

            if (getServer().getPluginManager().isPluginEnabled("PrivateMobsX")) {
                getLogger().warning("PrivateMobsX is present, but the public SPI from section 18.1 is not registered yet; private mob features remain disabled.");
            }

            CommandRegistrar.register(this, configs);
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
                handle.stateStore().loadAll().whenComplete((states, loadError) -> {
                    if (!isEnabled()) {
                        handle.close();
                        return;
                    }
                    getServer().getScheduler().runTask(this, () -> {
                        if (loadError != null) {
                            handle.close();
                            reportDatabaseFailure(loadError);
                            scheduleDatabaseRetry();
                            return;
                        }
                        StateStore reliableStore = new RetryingStateStore(this, handle.stateStore(), timers);
                        database = new DatabaseHandle(handle.dataSource(), reliableStore);
                        services.register(StateStore.class, reliableStore);
                        databaseReady = true;
                        nextDatabaseRetrySeconds = 5;
                        getLogger().info("MySQL connected; loaded " + states.size() + " persisted sector state(s)");
                    });
                });
            });
        });
    }

    private void reportDatabaseFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
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

    public boolean isDatabaseReady() { return databaseReady; }

    @Override
    public void onDisable() {
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
}

package com.gachicraft.ironfrontiers.bootstrap;

import com.gachicraft.ironfrontiers.util.NamedThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns isolated executors. Redis workers are not even created in single-server mode.
 * Bukkit API must never be called from any executor managed here.
 */
public final class ExecutorPools implements AutoCloseable {
    private final ExecutorService database;
    private final ExecutorService io;
    private final ExecutorService redis;

    public ExecutorPools(int databaseThreads, int ioThreads, boolean multiserverEnabled) {
        if (databaseThreads < 1 || ioThreads < 1) {
            throw new IllegalArgumentException("Executor thread counts must be positive");
        }
        this.database = Executors.newFixedThreadPool(databaseThreads,
                new NamedThreadFactory("IronFrontiers-Database"));
        this.io = Executors.newFixedThreadPool(ioThreads,
                new NamedThreadFactory("IronFrontiers-IO"));
        this.redis = multiserverEnabled
                ? Executors.newSingleThreadExecutor(new NamedThreadFactory("IronFrontiers-Redis"))
                : null;
    }

    public ExecutorService database() { return database; }
    public ExecutorService io() { return io; }
    public ExecutorService redis() {
        if (redis == null) throw new IllegalStateException("Redis executor is disabled");
        return redis;
    }
    public boolean isRedisEnabled() { return redis != null; }

    @Override
    public void close() {
        shutdown(database);
        shutdown(io);
        if (redis != null) shutdown(redis);
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

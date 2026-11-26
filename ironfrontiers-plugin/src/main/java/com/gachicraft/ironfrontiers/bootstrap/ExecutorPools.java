package com.gachicraft.ironfrontiers.bootstrap;

import com.gachicraft.ironfrontiers.util.NamedThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns isolated I/O pools; Redis workers are absent in single-server mode. */
public final class ExecutorPools implements AutoCloseable {
    private final ExecutorService database;
    private final ExecutorService io;
    private final ExecutorService redis;

    public ExecutorPools(int databaseThreads, int ioThreads, boolean multiserverEnabled) {
        if (databaseThreads < 1 || ioThreads < 1) throw new IllegalArgumentException("Thread counts must be positive");
        database = Executors.newFixedThreadPool(databaseThreads, new NamedThreadFactory("IronFrontiers-Database"));
        io = Executors.newFixedThreadPool(ioThreads, new NamedThreadFactory("IronFrontiers-IO"));
        redis = multiserverEnabled ? Executors.newSingleThreadExecutor(new NamedThreadFactory("IronFrontiers-Redis")) : null;
    }
    public ExecutorService database() { return database; }
    public ExecutorService io() { return io; }
    public ExecutorService redis() { if (redis == null) throw new IllegalStateException("Redis disabled"); return redis; }
    public boolean isRedisEnabled() { return redis != null; }
    @Override public void close() { shutdown(database); shutdown(io); if (redis != null) shutdown(redis); }
    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException exception) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}

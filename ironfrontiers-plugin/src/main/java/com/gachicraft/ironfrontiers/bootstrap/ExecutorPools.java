package com.gachicraft.ironfrontiers.bootstrap;

import com.gachicraft.ironfrontiers.util.NamedThreadFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns three bounded, isolated executors. Redis workers do not exist in single-server mode. */
public final class ExecutorPools implements AutoCloseable {
    private static final int DATABASE_QUEUE_CAPACITY = 4096;
    private static final int IO_QUEUE_CAPACITY = 1024;
    private static final int REDIS_QUEUE_CAPACITY = 2048;

    private final ExecutorService database;
    private final ExecutorService io;
    private final ExecutorService redis;

    public ExecutorPools(int databaseThreads, int ioThreads, boolean multiserverEnabled) {
        if (databaseThreads < 1 || ioThreads < 1) throw new IllegalArgumentException("Executor thread counts must be positive");
        database = createPool(databaseThreads, DATABASE_QUEUE_CAPACITY, "IronFrontiers-Database");
        io = createPool(ioThreads, IO_QUEUE_CAPACITY, "IronFrontiers-IO");
        redis = multiserverEnabled ? createPool(1, REDIS_QUEUE_CAPACITY, "IronFrontiers-Redis") : null;
    }

    private static ThreadPoolExecutor createPool(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public ExecutorService database() { return database; }
    public ExecutorService io() { return io; }
    public ExecutorService redis() {
        if (redis == null) throw new IllegalStateException("Redis executor is disabled");
        return redis;
    }
    public boolean isRedisEnabled() { return redis != null; }

    @Override public void close() {
        shutdown(database);
        shutdown(io);
        if (redis != null) shutdown(redis);
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

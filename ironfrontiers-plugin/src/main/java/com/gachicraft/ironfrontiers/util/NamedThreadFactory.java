package com.gachicraft.ironfrontiers.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates predictable, named daemon threads for the plugin's isolated I/O pools. */
public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, error) ->
                error.printStackTrace(System.err));
        return thread;
    }
}

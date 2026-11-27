package com.gachicraft.ironfrontiers.storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Async persistence boundary for globally shared sector state. */
public interface StateStore extends AutoCloseable {
    CompletableFuture<List<SectorPersistentState>> loadAll();
    CompletableFuture<Void> save(SectorPersistentState state);
    @Override void close();
}

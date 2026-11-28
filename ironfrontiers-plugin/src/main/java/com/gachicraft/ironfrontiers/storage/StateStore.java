package com.gachicraft.ironfrontiers.storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Async persistence boundary for globally shared sector state and KPM overrides. */
public interface StateStore extends AutoCloseable {
    CompletableFuture<List<SectorPersistentState>> loadAll();
    CompletableFuture<Void> save(SectorPersistentState state);
    CompletableFuture<List<SectorOverride>> loadOverrides();
    CompletableFuture<Void> saveOverride(SectorOverride override);
    @Override void close();
}

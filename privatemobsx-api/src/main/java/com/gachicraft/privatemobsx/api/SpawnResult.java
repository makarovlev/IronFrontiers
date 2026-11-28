package com.gachicraft.privatemobsx.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable outcome of a single private-mob spawn request. */
public final class SpawnResult {

    private final PrivateMobHandle handle;
    private final SpawnFailureReason failureReason;

    private SpawnResult(PrivateMobHandle handle, SpawnFailureReason failureReason) {
        this.handle = handle;
        this.failureReason = failureReason;
    }

    public static SpawnResult success(PrivateMobHandle handle) {
        return new SpawnResult(Objects.requireNonNull(handle, "handle"), null);
    }

    public static SpawnResult failure(SpawnFailureReason reason) {
        return new SpawnResult(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean isSuccess() {
        return handle != null;
    }

    public Optional<PrivateMobHandle> getHandle() {
        return Optional.ofNullable(handle);
    }

    public Optional<SpawnFailureReason> getFailureReason() {
        return Optional.ofNullable(failureReason);
    }
}

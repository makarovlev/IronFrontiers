package com.gachicraft.ironfrontiers.storage;

import com.gachicraft.ironfrontiers.api.SectorState;

/** Immutable persisted state; timer values are remaining seconds, not wall-clock timestamps. */
public record SectorPersistentState(String sectorId, SectorState state, Long stateLeftSeconds,
        long occupationLeftSeconds, Long destructionSavedSeconds, long resumeWindowLeftSeconds,
        long version, long leaderEpoch) {
    public SectorPersistentState {
        if (sectorId == null || sectorId.isBlank() || state == null
                || occupationLeftSeconds < 0 || resumeWindowLeftSeconds < 0
                || version < 0 || leaderEpoch < 0
                || (stateLeftSeconds != null && stateLeftSeconds < 0)
                || (destructionSavedSeconds != null && destructionSavedSeconds < 0)) {
            throw new IllegalArgumentException("Invalid persisted sector state");
        }
    }
}

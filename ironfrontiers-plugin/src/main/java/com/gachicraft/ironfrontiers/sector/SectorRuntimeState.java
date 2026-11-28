package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.api.SectorState;

/** Immutable runtime state for the pure sector state machine. */
public record SectorRuntimeState(
        SectorState state,
        Long stateLeftSeconds,
        long occupationLeftSeconds,
        Long destructionSavedSeconds,
        long resumeWindowLeftSeconds,
        long spawnPauseLeftSeconds,
        long heldOkSeconds,
        long version,
        boolean initializing
) {
    public SectorRuntimeState {
        if (state == null || occupationLeftSeconds < 0
                || resumeWindowLeftSeconds < 0 || spawnPauseLeftSeconds < 0
                || heldOkSeconds < 0 || version < 0
                || (stateLeftSeconds != null && stateLeftSeconds < 0)
                || (destructionSavedSeconds != null && destructionSavedSeconds < 0)) {
            throw new IllegalArgumentException("Invalid runtime sector state");
        }
        if ((state == SectorState.SAFE || state == SectorState.DESTRUCTION)
                && stateLeftSeconds == null) {
            throw new IllegalArgumentException(state + " requires a remaining timer");
        }
        if ((state == SectorState.ENEMY || state == SectorState.HELD)
                && stateLeftSeconds != null) {
            throw new IllegalArgumentException(state + " must not have a state timer");
        }
    }

    public static SectorRuntimeState enemy() {
        return new SectorRuntimeState(SectorState.ENEMY, null, 0,
                null, 0, 0, 0, 0, false);
    }

    public SectorRuntimeState withInitializing(boolean value) {
        return new SectorRuntimeState(state, stateLeftSeconds, occupationLeftSeconds,
                destructionSavedSeconds, resumeWindowLeftSeconds, spawnPauseLeftSeconds,
                heldOkSeconds, version, value);
    }
}

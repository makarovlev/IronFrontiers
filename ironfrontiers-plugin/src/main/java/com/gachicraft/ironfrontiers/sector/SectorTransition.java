package com.gachicraft.ironfrontiers.sector;

/** A state transition emitted by the pure sector automaton. */
public record SectorTransition(Kind kind, SectorRuntimeState previous, SectorRuntimeState current) {
    public SectorTransition {
        if (kind == null || previous == null || current == null) {
            throw new IllegalArgumentException("Transition fields must not be null");
        }
    }

    public enum Kind {
        CAPTURED,
        SAFE_ENDED_TO_HELD,
        DESTRUCTION_STARTED,
        DESTRUCTION_CANCELLED,
        LOST
    }
}

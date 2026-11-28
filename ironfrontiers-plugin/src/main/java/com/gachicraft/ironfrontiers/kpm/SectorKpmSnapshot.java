package com.gachicraft.ironfrontiers.kpm;

/** Immutable published aggregate KPM snapshot for one sector. */
public record SectorKpmSnapshot(String sectorId, double kpm, long timeSeconds) {
    public SectorKpmSnapshot {
        if (sectorId == null || sectorId.isBlank()
                || !Double.isFinite(kpm) || kpm < 0 || timeSeconds < 0) {
            throw new IllegalArgumentException("Invalid sector KPM snapshot");
        }
    }
}

package com.gachicraft.ironfrontiers.sector;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Immutable effective rules used by the pure sector state machine. */
public record SectorStatePolicy(
        int captureTenths,
        int heldTenths,
        int safeTimeSeconds,
        int destructionTimeSeconds,
        int occupationTimeSeconds,
        int destructionCancelHoldSeconds,
        int destructionResumeWindowSeconds,
        int spawnPauseAfterCaptureSeconds,
        boolean infiniteSafe,
        boolean freezeWhenEmpty,
        boolean strongholdActive,
        double strongholdSafeTimeMultiplier,
        double strongholdDestructionTimeMultiplier,
        double strongholdHeldKpmMultiplier
) {
    public SectorStatePolicy {
        if (captureTenths <= 0 || heldTenths <= 0
                || safeTimeSeconds <= 0 || destructionTimeSeconds <= 0
                || occupationTimeSeconds < 0
                || destructionCancelHoldSeconds < 1
                || destructionResumeWindowSeconds < 0
                || spawnPauseAfterCaptureSeconds < 0
                || !validMultiplier(strongholdSafeTimeMultiplier)
                || !validMultiplier(strongholdDestructionTimeMultiplier)
                || !validMultiplier(strongholdHeldKpmMultiplier)) {
            throw new IllegalArgumentException("Invalid sector state policy");
        }
    }

    public int effectiveHeldTenths() {
        double multiplier = strongholdActive ? strongholdHeldKpmMultiplier : 1.0;
        return BigDecimal.valueOf(heldTenths)
                .multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    public long effectiveSafeTimeSeconds() {
        return scaledSeconds(safeTimeSeconds,
                strongholdActive ? strongholdSafeTimeMultiplier : 1.0);
    }

    public long effectiveDestructionTimeSeconds() {
        return scaledSeconds(destructionTimeSeconds,
                strongholdActive ? strongholdDestructionTimeMultiplier : 1.0);
    }

    private static long scaledSeconds(int base, double multiplier) {
        return BigDecimal.valueOf(base)
                .multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static boolean validMultiplier(double multiplier) {
        return Double.isFinite(multiplier) && multiplier > 0.0;
    }
}

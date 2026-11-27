package com.gachicraft.ironfrontiers.kpm;

import java.util.Map;
import java.util.UUID;

/** Immutable personal KPM values published together with sector snapshots. */
public record PersonalKpmSnapshot(
        long timeSeconds,
        Map<PlayerSectorKey, Double> byPlayerAndSector,
        Map<UUID, Double> playerTotals
) {
    public PersonalKpmSnapshot {
        byPlayerAndSector = Map.copyOf(byPlayerAndSector);
        playerTotals = Map.copyOf(playerTotals);
    }

    public double playerKpm(UUID playerId, String sectorId) {
        return byPlayerAndSector.getOrDefault(new PlayerSectorKey(playerId, sectorId), 0.0);
    }

    public double playerKpmSum(UUID playerId) {
        return playerTotals.getOrDefault(playerId, 0.0);
    }

    public record PlayerSectorKey(UUID playerId, String sectorId) {
        public PlayerSectorKey {
            if (playerId == null || sectorId == null || sectorId.isBlank()) {
                throw new IllegalArgumentException("Invalid personal KPM key");
            }
        }
    }
}

package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.geometry.GridPoint;
import com.gachicraft.ironfrontiers.geometry.RasterizedSector;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable validated configuration of one sector. */
public record SectorDefinition(
        String id,
        String displayName,
        String worldName,
        UUID worldId,
        List<GridPoint> vertices,
        RasterizedSector geometry,
        int kpmCaptureTenths,
        int kpmHeldTenths,
        int safeTimeSeconds,
        int destructionTimeSeconds,
        int occupationTimeSeconds,
        boolean infiniteSafe,
        List<SpawnAnchor> spawnAnchors
) {
    public SectorDefinition {
        if (id == null || id.isBlank()
                || displayName == null || displayName.isBlank()
                || worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Sector identity fields must not be blank");
        }
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(geometry, "geometry");
        if (vertices == null || vertices.isEmpty()
                || kpmCaptureTenths <= 0 || kpmHeldTenths <= 0
                || safeTimeSeconds <= 0 || destructionTimeSeconds <= 0
                || occupationTimeSeconds < 0) {
            throw new IllegalArgumentException("Invalid sector definition: " + id);
        }
        vertices = List.copyOf(vertices);
        spawnAnchors = List.copyOf(spawnAnchors);
    }

    public double kpmCapture() {
        return kpmCaptureTenths / 10.0;
    }

    public double kpmHeld() {
        return kpmHeldTenths / 10.0;
    }

    public long area() {
        return geometry.area();
    }
}

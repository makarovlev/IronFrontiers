package com.gachicraft.ironfrontiers.sector;

/** Immutable configured mob-spawn anchor for a sector. */
public record SpawnAnchor(
        String slotId,
        String clusterId,
        double x,
        double y,
        double z,
        int respawnSeconds
) {
    public SpawnAnchor {
        if (slotId == null || slotId.isBlank()
                || clusterId == null || clusterId.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || respawnSeconds < 0) {
            throw new IllegalArgumentException("Invalid sector spawn anchor");
        }
    }

    public double distanceSquared(double blockX, double blockY, double blockZ) {
        double dx = x - blockX;
        double dy = y - blockY;
        double dz = z - blockZ;
        return dx * dx + dy * dy + dz * dz;
    }
}

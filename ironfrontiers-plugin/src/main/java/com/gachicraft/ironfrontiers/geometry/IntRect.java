package com.gachicraft.ironfrontiers.geometry;

/** Inclusive axis-aligned rectangle of block columns in XZ. */
public record IntRect(int minX, int minZ, int maxX, int maxZ) {
    public IntRect {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid rectangle bounds");
        }
    }

    public long area() {
        return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Euclidean distance from a grid point to the nearest included cell centre. */
    public double distanceTo(int x, int z) {
        long dx = x < minX ? (long) minX - x : x > maxX ? (long) x - maxX : 0L;
        long dz = z < minZ ? (long) minZ - z : z > maxZ ? (long) z - maxZ : 0L;
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }
}

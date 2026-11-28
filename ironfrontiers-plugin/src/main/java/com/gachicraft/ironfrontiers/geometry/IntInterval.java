package com.gachicraft.ironfrontiers.geometry;

/** Inclusive interval of integer X coordinates. */
public record IntInterval(int minX, int maxX) {
    public IntInterval {
        if (minX > maxX) {
            throw new IllegalArgumentException("minX must be <= maxX");
        }
    }

    public long length() {
        return (long) maxX - minX + 1L;
    }

    public boolean contains(int x) {
        return x >= minX && x <= maxX;
    }
}

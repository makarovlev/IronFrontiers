package com.gachicraft.ironfrontiers.kpm;

import java.util.Arrays;

/**
 * Fixed-size second-bucket ring window storing weighted kills in tenths.
 * add/sum are O(1) amortized and use only primitive arrays.
 */
public final class KpmWindow {
    private int[] buckets;
    private long totalTenths;
    private long currentSecond;
    private boolean initialized;

    public KpmWindow(int windowSeconds) {
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        buckets = new int[windowSeconds];
    }

    public void add(int tenths, long nowSecond) {
        if (tenths < 0) {
            throw new IllegalArgumentException("KPM weight must not be negative");
        }
        advance(nowSecond);
        int index = index(nowSecond);
        buckets[index] = Math.addExact(buckets[index], tenths);
        totalTenths = Math.addExact(totalTenths, tenths);
    }

    public long sumTenths(long nowSecond) {
        advance(nowSecond);
        return totalTenths;
    }

    public double sum(long nowSecond) {
        return sumTenths(nowSecond) / 10.0;
    }

    public int windowSeconds() {
        return buckets.length;
    }

    /**
     * Resizes the window while preserving only bucket values still inside the
     * newest min(oldSize,newSize) seconds. Called on the server thread during /if reload.
     */
    public void resize(int newWindowSeconds) {
        if (newWindowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (newWindowSeconds == buckets.length) {
            return;
        }

        int[] resized = new int[newWindowSeconds];
        long resizedTotal = 0L;
        if (initialized) {
            int retainedSeconds = Math.min(buckets.length, newWindowSeconds);
            for (int offset = 0; offset < retainedSeconds; offset++) {
                long second = currentSecond - offset;
                int value = buckets[index(second)];
                if (value != 0) {
                    int target = (int) Math.floorMod(second, (long) newWindowSeconds);
                    resized[target] = Math.addExact(resized[target], value);
                    resizedTotal = Math.addExact(resizedTotal, value);
                }
            }
        }

        buckets = resized;
        totalTenths = resizedTotal;
    }

    private void advance(long nowSecond) {
        if (!initialized) {
            initialized = true;
            currentSecond = nowSecond;
            return;
        }
        if (nowSecond < currentSecond) {
            throw new IllegalArgumentException("KPM time must be monotonic");
        }

        long elapsed = nowSecond - currentSecond;
        if (elapsed == 0) {
            return;
        }
        if (elapsed >= buckets.length) {
            Arrays.fill(buckets, 0);
            totalTenths = 0;
            currentSecond = nowSecond;
            return;
        }

        for (long offset = 1; offset <= elapsed; offset++) {
            int expiredIndex = index(currentSecond + offset);
            totalTenths -= buckets[expiredIndex];
            buckets[expiredIndex] = 0;
        }
        currentSecond = nowSecond;
    }

    private int index(long second) {
        return (int) Math.floorMod(second, (long) buckets.length);
    }
}

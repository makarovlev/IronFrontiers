package com.gachicraft.ironfrontiers.kpm;

import java.util.Arrays;

/**
 * Fixed-size second-bucket ring window storing weighted kills in tenths.
 * add/sum are O(1) amortized and use only primitive arrays.
 */
public final class KpmWindow {
    private final int[] buckets;
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

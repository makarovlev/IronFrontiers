package com.gachicraft.ironfrontiers.kpm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KpmWindowTest {
    @Test
    void sumsWeightedTenthsInTheCurrentSecond() {
        KpmWindow window = new KpmWindow(60);
        window.add(10, 100);
        window.add(15, 100);
        assertEquals(25L, window.sumTenths(100));
        assertEquals(2.5, window.sum(100), 0.00001);
    }

    @Test
    void expiresOnlyBucketsOutsideTheSlidingWindow() {
        KpmWindow window = new KpmWindow(3);
        window.add(10, 0);
        window.add(20, 1);
        assertEquals(30L, window.sumTenths(2));
        assertEquals(20L, window.sumTenths(3));
        assertEquals(0L, window.sumTenths(4));
    }

    @Test
    void clearsAllBucketsWhenSeveralSecondsAreSkipped() {
        KpmWindow window = new KpmWindow(60);
        window.add(40, 100);
        window.add(10, 101);
        assertEquals(50L, window.sumTenths(161));
    }

    @Test
    void rejectsNegativeWeightsAndNonMonotonicTime() {
        KpmWindow window = new KpmWindow(60);
        assertThrows(IllegalArgumentException.class, () -> window.add(-1, 0));
        window.add(10, 2);
        assertThrows(IllegalArgumentException.class, () -> window.sumTenths(1));
    }

    @Test
    void supportsLargeTimeValuesWithoutClockOfDayAssumptions() {
        KpmWindow window = new KpmWindow(60);
        long now = Long.MAX_VALUE - 100;
        window.add(10, now);
        assertEquals(10L, window.sumTenths(now));
    }
}

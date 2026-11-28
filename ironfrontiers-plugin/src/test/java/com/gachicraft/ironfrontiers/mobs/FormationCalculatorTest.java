package com.gachicraft.ironfrontiers.mobs;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FormationCalculatorTest {
    @Test
    void createsExactlyTheRequestedNumberOfPointsForEverySupportedClusterSize() {
        for (int count = 1; count <= 10; count++) {
            var points = FormationCalculator.calculate(count, 2.5, 0.6,
                    0, false, new Random(count));
            assertEquals(count, points.size(), "count=" + count);
        }
    }

    @Test
    void usesTheLargerOfConfiguredSpacingAndMobWidthSpacing() {
        var points = FormationCalculator.calculate(2, 1.0, 2.0,
                0, false, new Random(1));
        assertEquals(-1.5, points.get(0).x(), 0.000001);
        assertEquals(1.5, points.get(1).x(), 0.000001);
    }

    @Test
    void singletonIsCenteredAndFixedFormationsAreDeterministicWithoutRandomness() {
        var singleton = FormationCalculator.calculate(1, 2.5, 0.6,
                0, false, new Random(1));
        assertEquals(new FormationCalculator.Offset(0, 0), singleton.getFirst());

        var square = FormationCalculator.calculate(4, 2.0, 0.6,
                0, false, new Random(1));
        assertEquals(4, square.stream().distinct().count());
        assertTrue(square.stream().allMatch(point ->
                Math.abs(Math.abs(point.x()) - 1.0) < 0.000001
                        && Math.abs(Math.abs(point.z()) - 1.0) < 0.000001));
    }

    @Test
    void rejectsUnsupportedClusterSizesAndInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () ->
                FormationCalculator.calculate(0, 2.5, 0.6, 0, false, new Random()));
        assertThrows(IllegalArgumentException.class, () ->
                FormationCalculator.calculate(2, Double.NaN, 0.6, 0, false, new Random()));
    }
}

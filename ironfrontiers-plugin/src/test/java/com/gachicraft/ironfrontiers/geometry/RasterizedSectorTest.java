package com.gachicraft.ironfrontiers.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class RasterizedSectorTest {
    @Test
    void doesNotJoinRectanglesAcrossMissingRowsAtMinimumIntegerCoordinate() {
        TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
        rows.put(Integer.MIN_VALUE, List.of(new IntInterval(0, 1)));
        rows.put(Integer.MIN_VALUE + 2, List.of(new IntInterval(0, 1)));

        RasterizedSector sector = new RasterizedSector(rows);

        assertEquals(4L, sector.area());
        assertEquals(2, sector.rectangles().size());
        assertEquals(new IntRect(0, Integer.MIN_VALUE, 1, Integer.MIN_VALUE),
                sector.rectangles().get(0));
        assertEquals(new IntRect(0, Integer.MIN_VALUE + 2, 1, Integer.MIN_VALUE + 2),
                sector.rectangles().get(1));
    }
}

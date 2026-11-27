package com.gachicraft.ironfrontiers.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AxisAlignedPolygonTest {
    @Test
    void matchesDocumentedThirtySixCellExample() {
        RasterizedSector sector = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(3, 0), new GridPoint(3, 2),
                new GridPoint(5, 2), new GridPoint(5, -4), new GridPoint(0, -4)));

        assertEquals(36L, sector.area());
        for (int z = -4; z <= 0; z++) {
            assertEquals(List.of(new IntInterval(0, 5)), sector.rows().get(z));
        }
        assertEquals(List.of(new IntInterval(3, 5)), sector.rows().get(1));
        assertEquals(List.of(new IntInterval(3, 5)), sector.rows().get(2));
    }

    @Test
    void handlesSingleCellAndTwoPointLines() {
        assertEquals(1L, AxisAlignedPolygon.rasterize(List.of(new GridPoint(7, -2))).area());
        assertEquals(5L, AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(2, 4), new GridPoint(6, 4))).area());
        assertEquals(4L, AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(-1, 0), new GridPoint(-1, 3))).area());
    }

    @Test
    void handlesMonotoneMultiVertexLines() {
        RasterizedSector horizontal = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 5), new GridPoint(2, 5), new GridPoint(7, 5)));
        RasterizedSector vertical = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(4, -3), new GridPoint(4, 1), new GridPoint(4, 6)));

        assertEquals(8L, horizontal.area());
        assertEquals(10L, vertical.area());
    }

    @Test
    void normalizesRedundantCollinearVerticesWithoutChangingCells() {
        RasterizedSector sector = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(2, 0), new GridPoint(3, 0),
                new GridPoint(3, 2), new GridPoint(0, 2)));

        assertEquals(12L, sector.area());
        assertEquals(1, sector.rectangles().size());
        assertEquals(new IntRect(0, 0, 3, 2), sector.rectangles().getFirst());
    }

    @Test
    void mergesIdenticalRowIntervalsIntoRectangles() {
        RasterizedSector sector = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(5, 0),
                new GridPoint(5, 2), new GridPoint(0, 2)));

        assertEquals(18L, sector.area());
        assertEquals(List.of(new IntRect(0, 0, 5, 2)), sector.rectangles());
    }

    @Test
    void handlesConcaveLAndPocketShapes() {
        RasterizedSector lShape = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(4, 0), new GridPoint(4, 1),
                new GridPoint(1, 1), new GridPoint(1, 4), new GridPoint(0, 4)));
        RasterizedSector pocket = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(5, 0), new GridPoint(5, 5),
                new GridPoint(4, 5), new GridPoint(4, 1), new GridPoint(1, 1),
                new GridPoint(1, 5), new GridPoint(0, 5)));

        assertEquals(14L, lShape.area());
        assertTrue(pocket.contains(2, 0));
        assertFalse(pocket.contains(2, 3));
    }

    @Test
    void rejectsDiagonalDuplicateAndReversingEdges() {
        assertThrows(IllegalArgumentException.class, () -> AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(2, 1), new GridPoint(2, 2))));
        assertThrows(IllegalArgumentException.class, () -> AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(1, 0), new GridPoint(1, 0))));
        assertThrows(IllegalArgumentException.class, () -> AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(3, 0), new GridPoint(1, 0))));
    }

    @Test
    void rejectsSelfIntersectionAndNonAdjacentTouching() {
        assertThrows(IllegalArgumentException.class, () -> AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(4, 0), new GridPoint(4, 4),
                new GridPoint(2, 4), new GridPoint(2, -1), new GridPoint(1, -1),
                new GridPoint(1, 3), new GridPoint(0, 3))));
    }
}

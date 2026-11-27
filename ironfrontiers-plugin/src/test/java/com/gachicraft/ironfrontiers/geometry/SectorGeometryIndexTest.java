package com.gachicraft.ironfrontiers.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SectorGeometryIndexTest {
    @Test
    void findsSectorAtBlockAndHandlesNegativeChunkCoordinates() {
        UUID world = UUID.randomUUID();
        RasterizedSector first = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(-17, -17), new GridPoint(-15, -17),
                new GridPoint(-15, -15), new GridPoint(-17, -15)));
        SectorGeometryIndex.Builder builder = new SectorGeometryIndex.Builder();
        builder.add(world, first);
        SectorGeometryIndex index = builder.build();

        assertTrue(index.sectorAt(world, -17, -17) >= 0);
        assertTrue(index.sectorAt(world, -15, -15) >= 0);
        assertEquals(-1, index.sectorAt(world, -14, -15));
        assertEquals(-1, index.sectorAt(UUID.randomUUID(), -17, -17));
    }

    @Test
    void rejectsOverlappingSectorsInSameWorldButAllowsDifferentWorlds() {
        UUID world = UUID.randomUUID();
        RasterizedSector area = AxisAlignedPolygon.rasterize(List.of(new GridPoint(0, 0),
                new GridPoint(2, 0), new GridPoint(2, 2), new GridPoint(0, 2)));
        SectorGeometryIndex.Builder builder = new SectorGeometryIndex.Builder();
        builder.add(world, area);
        assertThrows(SectorGeometryIndex.OverlapException.class, () -> builder.add(world, area));
        assertDoesNotThrow(() -> builder.add(UUID.randomUUID(), area));
    }

    @Test
    void nearQueryUsesExactDistanceAfterChunkFiltering() {
        UUID world = UUID.randomUUID();
        SectorGeometryIndex.Builder builder = new SectorGeometryIndex.Builder();
        builder.add(world, AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(32, 0), new GridPoint(34, 0),
                new GridPoint(34, 2), new GridPoint(32, 2))));
        SectorGeometryIndex index = builder.build();

        assertArrayEquals(new int[]{0}, index.sectorsNear(world, 20, 1, 12));
        assertArrayEquals(new int[0], index.sectorsNear(world, 20, 1, 11));
        assertEquals(12.0, index.distanceToSector(0, 20, 1), 0.0001);
    }
}

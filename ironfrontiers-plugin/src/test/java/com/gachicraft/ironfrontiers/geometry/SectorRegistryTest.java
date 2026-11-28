package com.gachicraft.ironfrontiers.geometry;

import com.gachicraft.ironfrontiers.sector.SectorDefinition;
import com.gachicraft.ironfrontiers.sector.SectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SectorRegistryTest {
    @Test
    void idsAreCaseInsensitiveAndSectorLookupUsesTheChunkIndex() {
        UUID world = UUID.randomUUID();
        RasterizedSector geometry = AxisAlignedPolygon.rasterize(List.of(
                new GridPoint(0, 0), new GridPoint(2, 0),
                new GridPoint(2, 2), new GridPoint(0, 2)));
        SectorDefinition definition = new SectorDefinition("Aa01", "&6Sector AA01",
                "world_war", world, List.of(new GridPoint(0, 0), new GridPoint(2, 0),
                new GridPoint(2, 2), new GridPoint(0, 2)), geometry,
                450, 230, 3600, 300, 300, false, List.of());
        SectorGeometryIndex.Builder indexBuilder = new SectorGeometryIndex.Builder();
        indexBuilder.add(world, geometry);
        SectorRegistry registry = new SectorRegistry(List.of(definition), indexBuilder.build());

        assertTrue(registry.get("aa01").isPresent());
        assertTrue(registry.get("AA01").isPresent());
        assertEquals("Aa01", registry.sectorAtDefinition(world, 1, 1).orElseThrow().id());
        assertFalse(registry.get("missing").isPresent());
    }

    @Test
    void rejectsDuplicateIdsIgnoringCase() {
        UUID world = UUID.randomUUID();
        RasterizedSector geometry = AxisAlignedPolygon.rasterize(List.of(new GridPoint(0, 0)));
        SectorDefinition first = definition("aa01", world, geometry);
        SectorDefinition duplicate = definition("AA01", world, geometry);
        SectorGeometryIndex.Builder builder = new SectorGeometryIndex.Builder();
        builder.add(world, geometry);
        assertThrows(IllegalArgumentException.class,
                () -> new SectorRegistry(List.of(first, duplicate), builder.build()));
    }

    private static SectorDefinition definition(String id, UUID world, RasterizedSector geometry) {
        return new SectorDefinition(id, id, "world_war", world, List.of(new GridPoint(0, 0)),
                geometry, 450, 230, 3600, 300, 300, false, List.of());
    }
}

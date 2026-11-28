package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.geometry.SectorGeometryIndex;

import java.util.*;

/** Immutable registry of valid sectors and their geometry index. */
public final class SectorRegistry {
    private final Map<String, SectorDefinition> byId;
    private final List<SectorDefinition> ordered;
    private final SectorGeometryIndex geometryIndex;

    public SectorRegistry(List<SectorDefinition> sectors, SectorGeometryIndex geometryIndex) {
        Objects.requireNonNull(sectors, "sectors");
        this.geometryIndex = Objects.requireNonNull(geometryIndex, "geometryIndex");

        LinkedHashMap<String, SectorDefinition> indexed = new LinkedHashMap<>();
        for (SectorDefinition sector : sectors) {
            String key = normalizeId(sector.id());
            if (indexed.putIfAbsent(key, sector) != null) {
                throw new IllegalArgumentException("Duplicate sector id: " + sector.id());
            }
        }
        this.byId = Collections.unmodifiableMap(indexed);
        this.ordered = List.copyOf(indexed.values());
        if (geometryIndex.size() != ordered.size()) {
            throw new IllegalArgumentException("Sector definitions and geometry index are out of sync");
        }
    }

    public Optional<SectorDefinition> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(normalizeId(id)));
    }

    public boolean contains(String id) {
        return id != null && byId.containsKey(normalizeId(id));
    }

    public Collection<SectorDefinition> sectors() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    public int sectorAt(UUID worldId, int x, int z) {
        return geometryIndex.sectorAt(worldId, x, z);
    }

    public Optional<SectorDefinition> sectorAtDefinition(UUID worldId, int x, int z) {
        int index = geometryIndex.sectorAt(worldId, x, z);
        return index < 0 ? Optional.empty() : Optional.of(ordered.get(index));
    }

    public List<SectorDefinition> sectorsNear(UUID worldId, int x, int z, int radius) {
        int[] indexes = geometryIndex.sectorsNear(worldId, x, z, radius);
        if (indexes.length == 0) {
            return List.of();
        }
        List<SectorDefinition> result = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            result.add(ordered.get(index));
        }
        return List.copyOf(result);
    }

    public SectorGeometryIndex geometryIndex() {
        return geometryIndex;
    }

    private static String normalizeId(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}

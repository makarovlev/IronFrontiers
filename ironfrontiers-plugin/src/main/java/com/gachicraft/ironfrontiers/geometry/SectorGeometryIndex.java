package com.gachicraft.ironfrontiers.geometry;

import java.util.*;

/**
 * Immutable O(1) sector lookup index. Chunk keys use a primitive-long map so
 * sectorAt does not allocate a key object for each player movement query.
 */
public final class SectorGeometryIndex {
    private final Map<UUID, WorldChunks> worlds;
    private final List<Entry> entries;

    private SectorGeometryIndex(Map<UUID, WorldChunks> worlds, List<Entry> entries) {
        this.worlds = Map.copyOf(worlds);
        this.entries = List.copyOf(entries);
    }

    /** Returns the insertion-order sector index, or -1 when the column is outside all sectors. */
    public int sectorAt(UUID worldId, int x, int z) {
        WorldChunks world = worlds.get(worldId);
        if (world == null) {
            return -1;
        }
        long key = chunkKey(x >> 4, z >> 4);
        List<Coverage> coverages = world.chunks.get(key);
        if (coverages == null) {
            return -1;
        }

        int localX = x & 15;
        int localZ = z & 15;
        int bit = (localZ << 4) | localX;
        int word = bit >>> 6;
        long mask = 1L << (bit & 63);
        for (Coverage coverage : coverages) {
            if (coverage.full || (coverage.mask[word] & mask) != 0L) {
                return coverage.sectorIndex;
            }
        }
        return -1;
    }

    /** Returns sector indices with at least one cell within the given Euclidean radius. */
    public int[] sectorsNear(UUID worldId, int x, int z, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        WorldChunks world = worlds.get(worldId);
        if (world == null) {
            return new int[0];
        }

        long minBlockX = Math.max(Integer.MIN_VALUE, (long) x - radius);
        long maxBlockX = Math.min(Integer.MAX_VALUE, (long) x + radius);
        long minBlockZ = Math.max(Integer.MIN_VALUE, (long) z - radius);
        long maxBlockZ = Math.min(Integer.MAX_VALUE, (long) z + radius);
        int minChunkX = (int) Math.floorDiv(minBlockX, 16L);
        int maxChunkX = (int) Math.floorDiv(maxBlockX, 16L);
        int minChunkZ = (int) Math.floorDiv(minBlockZ, 16L);
        int maxChunkZ = (int) Math.floorDiv(maxBlockZ, 16L);
        BitSet candidates = new BitSet(entries.size());

        long chunksX = (long) maxChunkX - minChunkX + 1L;
        long chunksZ = (long) maxChunkZ - minChunkZ + 1L;
        if (chunksX * chunksZ > Math.max(4096L, (long) entries.size() * 4L)) {
            // Very large radius: avoid walking millions of empty chunk keys.
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).worldId.equals(worldId)) candidates.set(i);
            }
        } else {
            for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    List<Coverage> coverages = world.chunks.get(chunkKey((int) chunkX, (int) chunkZ));
                    if (coverages != null) {
                        for (Coverage coverage : coverages) {
                            candidates.set(coverage.sectorIndex);
                        }
                    }
                }
            }
        }

        int[] buffer = new int[candidates.cardinality()];
        int count = 0;
        for (int index = candidates.nextSetBit(0); index >= 0; index = candidates.nextSetBit(index + 1)) {
            if (distanceToSector(index, x, z) <= radius) {
                buffer[count++] = index;
            }
        }
        return Arrays.copyOf(buffer, count);
    }

    public double distanceToSector(int sectorIndex, int x, int z) {
        Entry entry = entries.get(sectorIndex);
        double minimum = Double.POSITIVE_INFINITY;
        for (IntRect rectangle : entry.geometry.rectangles()) {
            minimum = Math.min(minimum, rectangle.distanceTo(x, z));
            if (minimum == 0.0) {
                break;
            }
        }
        return minimum;
    }

    public long area(int sectorIndex) {
        return entries.get(sectorIndex).geometry.area();
    }

    public RasterizedSector geometry(int sectorIndex) {
        return entries.get(sectorIndex).geometry;
    }

    public UUID worldId(int sectorIndex) {
        return entries.get(sectorIndex).worldId;
    }

    public int size() {
        return entries.size();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
    }

    public static final class Builder {
        private final Map<UUID, MutableWorld> worlds = new HashMap<>();
        private final List<Entry> entries = new ArrayList<>();

        /** Adds a sector, rejecting any cell already owned by a sector in the same world. */
        public int add(UUID worldId, RasterizedSector geometry) {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(geometry, "geometry");
            MutableWorld world = worlds.computeIfAbsent(worldId, ignored -> new MutableWorld());

            geometry.forEachCell((x, z) -> {
                MutableChunk chunk = world.chunks.get(chunkKey(x >> 4, z >> 4));
                if (chunk != null && chunk.contains((x & 15), (z & 15))) {
                    throw new OverlapException("Sector overlaps an existing sector at " + x + "," + z);
                }
            });

            int sectorIndex = entries.size();
            geometry.forEachCell((x, z) -> {
                long key = chunkKey(x >> 4, z >> 4);
                MutableChunk chunk = world.chunks.get(key);
                if (chunk == null) {
                    chunk = new MutableChunk();
                    world.chunks.put(key, chunk);
                }
                chunk.add(sectorIndex, x & 15, z & 15);
            });
            entries.add(new Entry(worldId, geometry));
            return sectorIndex;
        }

        public SectorGeometryIndex build() {
            Map<UUID, WorldChunks> builtWorlds = new HashMap<>();
            for (Map.Entry<UUID, MutableWorld> worldEntry : worlds.entrySet()) {
                LongObjectMap<List<Coverage>> immutableChunks =
                        new LongObjectMap<>(worldEntry.getValue().chunks.size());
                worldEntry.getValue().chunks.forEach((key, chunk) ->
                        immutableChunks.put(key, chunk.toImmutable()));
                builtWorlds.put(worldEntry.getKey(), new WorldChunks(immutableChunks));
            }
            return new SectorGeometryIndex(builtWorlds, entries);
        }
    }

    private record Entry(UUID worldId, RasterizedSector geometry) { }
    private record WorldChunks(LongObjectMap<List<Coverage>> chunks) { }

    private static final class MutableWorld {
        private final LongObjectMap<MutableChunk> chunks = new LongObjectMap<>();
    }

    private static final class MutableChunk {
        private final Map<Integer, long[]> sectors = new HashMap<>();

        private boolean contains(int x, int z) {
            int bit = (z << 4) | x;
            int word = bit >>> 6;
            long mask = 1L << (bit & 63);
            for (long[] values : sectors.values()) {
                if ((values[word] & mask) != 0L) {
                    return true;
                }
            }
            return false;
        }

        private void add(int sectorIndex, int x, int z) {
            long[] mask = sectors.computeIfAbsent(sectorIndex, ignored -> new long[4]);
            int bit = (z << 4) | x;
            mask[bit >>> 6] |= 1L << (bit & 63);
        }

        private List<Coverage> toImmutable() {
            List<Coverage> result = new ArrayList<>(sectors.size());
            for (Map.Entry<Integer, long[]> entry : sectors.entrySet()) {
                long[] copy = entry.getValue().clone();
                boolean full = copy[0] == -1L && copy[1] == -1L && copy[2] == -1L && copy[3] == -1L;
                result.add(new Coverage(entry.getKey(), full, copy));
            }
            result.sort(Comparator.comparingInt(Coverage::sectorIndex));
            return List.copyOf(result);
        }
    }

    private record Coverage(int sectorIndex, boolean full, long[] mask) { }

    public static final class OverlapException extends IllegalArgumentException {
        public OverlapException(String message) {
            super(message);
        }
    }
}

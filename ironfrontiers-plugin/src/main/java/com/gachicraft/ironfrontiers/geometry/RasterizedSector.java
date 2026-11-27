package com.gachicraft.ironfrontiers.geometry;

import java.util.*;

/**
 * Immutable rasterized sector. Each row maps to sorted, merged inclusive X intervals.
 * Rectangles are formed by joining identical intervals in adjacent Z rows.
 */
public final class RasterizedSector {
    private final NavigableMap<Integer, List<IntInterval>> rows;
    private final List<IntRect> rectangles;
    private final long area;
    private final IntRect bounds;

    RasterizedSector(NavigableMap<Integer, List<IntInterval>> source) {
        TreeMap<Integer, List<IntInterval>> copy = new TreeMap<>();
        long cellCount = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;

        for (Map.Entry<Integer, List<IntInterval>> entry : source.entrySet()) {
            List<IntInterval> intervals = List.copyOf(entry.getValue());
            if (intervals.isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), intervals);
            for (IntInterval interval : intervals) {
                cellCount = Math.addExact(cellCount, interval.length());
                minX = Math.min(minX, interval.minX());
                maxX = Math.max(maxX, interval.maxX());
            }
        }

        if (copy.isEmpty()) {
            throw new IllegalArgumentException("A sector must contain at least one cell");
        }

        this.rows = Collections.unmodifiableNavigableMap(copy);
        this.area = cellCount;
        this.bounds = new IntRect(minX, copy.firstKey(), maxX, copy.lastKey());
        this.rectangles = buildRectangles(copy);
    }

    public NavigableMap<Integer, List<IntInterval>> rows() {
        return rows;
    }

    public List<IntRect> rectangles() {
        return rectangles;
    }

    public long area() {
        return area;
    }

    public IntRect bounds() {
        return bounds;
    }

    public boolean contains(int x, int z) {
        List<IntInterval> intervals = rows.get(z);
        if (intervals == null) {
            return false;
        }
        int low = 0;
        int high = intervals.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            IntInterval interval = intervals.get(middle);
            if (x < interval.minX()) {
                high = middle - 1;
            } else if (x > interval.maxX()) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    public void forEachCell(CellConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (Map.Entry<Integer, List<IntInterval>> row : rows.entrySet()) {
            int z = row.getKey();
            for (IntInterval interval : row.getValue()) {
                for (long x = interval.minX(); x <= interval.maxX(); x++) {
                    consumer.accept((int) x, z);
                }
            }
        }
    }

    private static List<IntRect> buildRectangles(NavigableMap<Integer, List<IntInterval>> rows) {
        Map<IntInterval, Integer> active = new HashMap<>();
        List<IntRect> result = new ArrayList<>();
        int previousZ = Integer.MIN_VALUE;

        for (Map.Entry<Integer, List<IntInterval>> row : rows.entrySet()) {
            int z = row.getKey();
            if (previousZ != Integer.MIN_VALUE && (long) z != (long) previousZ + 1L) {
                finishActive(active, result, previousZ);
            }

            Set<IntInterval> current = new HashSet<>(row.getValue());
            Iterator<Map.Entry<IntInterval, Integer>> iterator = active.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<IntInterval, Integer> entry = iterator.next();
                if (!current.contains(entry.getKey())) {
                    result.add(new IntRect(entry.getKey().minX(), entry.getValue(),
                            entry.getKey().maxX(), previousZ));
                    iterator.remove();
                }
            }

            for (IntInterval interval : row.getValue()) {
                active.putIfAbsent(interval, z);
            }
            previousZ = z;
        }

        finishActive(active, result, previousZ);
        result.sort(Comparator.comparingInt(IntRect::minZ)
                .thenComparingInt(IntRect::minX)
                .thenComparingInt(IntRect::maxZ)
                .thenComparingInt(IntRect::maxX));
        return List.copyOf(result);
    }

    private static void finishActive(Map<IntInterval, Integer> active, List<IntRect> result, int endZ) {
        for (Map.Entry<IntInterval, Integer> entry : active.entrySet()) {
            result.add(new IntRect(entry.getKey().minX(), entry.getValue(),
                    entry.getKey().maxX(), endZ));
        }
        active.clear();
    }

    @FunctionalInterface
    public interface CellConsumer {
        void accept(int x, int z);
    }
}

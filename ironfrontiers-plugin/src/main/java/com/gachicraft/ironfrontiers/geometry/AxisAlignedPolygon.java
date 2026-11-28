package com.gachicraft.ironfrontiers.geometry;

import java.util.*;

/**
 * Validates and rasterizes an orthogonal XZ contour using inclusive boundary
 * cells and the half-open vertical-edge parity rule from section 7.2.
 */
public final class AxisAlignedPolygon {
    private AxisAlignedPolygon() { }

    public static RasterizedSector rasterize(List<GridPoint> input) {
        List<GridPoint> vertices = normalizeInput(input);
        if (vertices.size() == 1) {
            return singleCell(vertices.getFirst());
        }
        if (vertices.size() == 2) {
            return line(vertices.get(0), vertices.get(1));
        }

        if (isAxisAlignedLine(vertices)) {
            return rasterizeMonotoneLine(vertices);
        }

        List<GridPoint> polygon = simplifyPolygon(vertices);
        validateEdges(polygon);
        validateNonAdjacentEdges(polygon);
        return rasterizePolygon(polygon);
    }

    private static List<GridPoint> normalizeInput(List<GridPoint> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("A sector contour must contain at least one vertex");
        }
        List<GridPoint> vertices = new ArrayList<>(input.size());
        for (GridPoint point : input) {
            if (point == null) {
                throw new IllegalArgumentException("Contour contains a null vertex");
            }
            vertices.add(point);
        }
        if (vertices.size() > 2 && vertices.getFirst().equals(vertices.getLast())) {
            throw new IllegalArgumentException("The first vertex must not be repeated at the end; closure is implicit");
        }
        return vertices;
    }

    private static boolean isAxisAlignedLine(List<GridPoint> vertices) {
        GridPoint first = vertices.getFirst();
        boolean sameX = true;
        boolean sameZ = true;
        for (GridPoint point : vertices) {
            sameX &= point.x() == first.x();
            sameZ &= point.z() == first.z();
        }
        return sameX || sameZ;
    }

    private static RasterizedSector rasterizeMonotoneLine(List<GridPoint> vertices) {
        boolean vertical = vertices.stream().allMatch(point -> point.x() == vertices.getFirst().x());
        int previous = vertical ? vertices.getFirst().z() : vertices.getFirst().x();
        int direction = 0;
        int min = previous;
        int max = previous;

        for (int i = 1; i < vertices.size(); i++) {
            GridPoint point = vertices.get(i);
            int current = vertical ? point.z() : point.x();
            if (current == previous) {
                throw new IllegalArgumentException("Coincident adjacent vertices are not allowed");
            }
            int nextDirection = Integer.compare(current, previous);
            if (direction == 0) {
                direction = nextDirection;
            } else if (direction != nextDirection) {
                throw new IllegalArgumentException("A linear sector must be monotone; the contour reverses direction");
            }
            min = Math.min(min, current);
            max = Math.max(max, current);
            previous = current;
        }

        TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
        if (vertical) {
            for (long z = min; z <= max; z++) {
                rows.put((int) z, List.of(new IntInterval(vertices.getFirst().x(), vertices.getFirst().x())));
            }
        } else {
            rows.put(vertices.getFirst().z(), List.of(new IntInterval(min, max)));
        }
        return new RasterizedSector(rows);
    }

    private static RasterizedSector singleCell(GridPoint point) {
        TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
        rows.put(point.z(), List.of(new IntInterval(point.x(), point.x())));
        return new RasterizedSector(rows);
    }

    private static RasterizedSector line(GridPoint first, GridPoint second) {
        if (first.equals(second)) {
            throw new IllegalArgumentException("A two-vertex line must have distinct endpoints");
        }
        if (first.x() == second.x()) {
            int min = Math.min(first.z(), second.z());
            int max = Math.max(first.z(), second.z());
            TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
            for (long z = min; z <= max; z++) {
                rows.put((int) z, List.of(new IntInterval(first.x(), first.x())));
            }
            return new RasterizedSector(rows);
        }
        if (first.z() == second.z()) {
            TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
            rows.put(first.z(), List.of(new IntInterval(
                    Math.min(first.x(), second.x()), Math.max(first.x(), second.x()))));
            return new RasterizedSector(rows);
        }
        throw new IllegalArgumentException("A linear sector must be horizontal or vertical");
    }

    private static List<GridPoint> simplifyPolygon(List<GridPoint> source) {
        List<GridPoint> vertices = new ArrayList<>(source);
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("A polygon needs at least three vertices");
        }

        boolean changed;
        do {
            changed = false;
            int size = vertices.size();
            if (size < 3) {
                throw new IllegalArgumentException("Contour collapses to a line");
            }
            for (int i = 0; i < size; i++) {
                GridPoint previous = vertices.get((i - 1 + size) % size);
                GridPoint current = vertices.get(i);
                GridPoint next = vertices.get((i + 1) % size);

                if (previous.equals(current) || current.equals(next)) {
                    throw new IllegalArgumentException("Coincident adjacent vertices are not allowed");
                }

                boolean horizontal = previous.z() == current.z() && current.z() == next.z();
                boolean vertical = previous.x() == current.x() && current.x() == next.x();
                if (!horizontal && !vertical) {
                    continue;
                }

                int firstDirection = horizontal
                        ? Integer.compare(current.x(), previous.x())
                        : Integer.compare(current.z(), previous.z());
                int secondDirection = horizontal
                        ? Integer.compare(next.x(), current.x())
                        : Integer.compare(next.z(), current.z());

                if (firstDirection != secondDirection) {
                    throw new IllegalArgumentException("Adjacent edges reverse direction by 180 degrees");
                }

                vertices.remove(i);
                changed = true;
                break;
            }
        } while (changed);

        return List.copyOf(vertices);
    }

    private static void validateEdges(List<GridPoint> vertices) {
        int size = vertices.size();
        for (int i = 0; i < size; i++) {
            GridPoint a = vertices.get(i);
            GridPoint b = vertices.get((i + 1) % size);
            boolean sameX = a.x() == b.x();
            boolean sameZ = a.z() == b.z();
            if (sameX == sameZ) {
                throw new IllegalArgumentException("Every edge must be non-zero and strictly axis-aligned");
            }
        }
    }

    private static void validateNonAdjacentEdges(List<GridPoint> vertices) {
        int size = vertices.size();
        for (int i = 0; i < size; i++) {
            GridPoint a1 = vertices.get(i);
            GridPoint a2 = vertices.get((i + 1) % size);
            for (int j = i + 1; j < size; j++) {
                if (j == i + 1 || (i == 0 && j == size - 1)) {
                    continue;
                }
                GridPoint b1 = vertices.get(j);
                GridPoint b2 = vertices.get((j + 1) % size);
                if (segmentsIntersect(a1, a2, b1, b2)) {
                    throw new IllegalArgumentException("Non-adjacent edges intersect, touch or overlap");
                }
            }
        }
    }

    private static boolean segmentsIntersect(GridPoint a1, GridPoint a2, GridPoint b1, GridPoint b2) {
        boolean aVertical = a1.x() == a2.x();
        boolean bVertical = b1.x() == b2.x();

        if (aVertical && bVertical) {
            if (a1.x() != b1.x()) {
                return false;
            }
            return rangesOverlap(a1.z(), a2.z(), b1.z(), b2.z());
        }
        if (!aVertical && !bVertical) {
            if (a1.z() != b1.z()) {
                return false;
            }
            return rangesOverlap(a1.x(), a2.x(), b1.x(), b2.x());
        }

        GridPoint v1 = aVertical ? a1 : b1;
        GridPoint v2 = aVertical ? a2 : b2;
        GridPoint h1 = aVertical ? b1 : a1;
        GridPoint h2 = aVertical ? b2 : a2;
        return between(v1.x(), h1.x(), h2.x()) && between(h1.z(), v1.z(), v2.z());
    }

    private static boolean rangesOverlap(int a1, int a2, int b1, int b2) {
        return Math.max(Math.min(a1, a2), Math.min(b1, b2))
                <= Math.min(Math.max(a1, a2), Math.max(b1, b2));
    }

    private static boolean between(int value, int a, int b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }

    private static RasterizedSector rasterizePolygon(List<GridPoint> vertices) {
        TreeMap<Integer, List<IntInterval>> rows = new TreeMap<>();
        List<VerticalEdge> verticalEdges = new ArrayList<>();
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int i = 0; i < vertices.size(); i++) {
            GridPoint a = vertices.get(i);
            GridPoint b = vertices.get((i + 1) % vertices.size());
            minZ = Math.min(minZ, Math.min(a.z(), b.z()));
            maxZ = Math.max(maxZ, Math.max(a.z(), b.z()));

            if (a.z() == b.z()) {
                add(rows, a.z(), new IntInterval(Math.min(a.x(), b.x()), Math.max(a.x(), b.x())));
            } else {
                VerticalEdge edge = new VerticalEdge(a.x(), Math.min(a.z(), b.z()), Math.max(a.z(), b.z()));
                verticalEdges.add(edge);
                for (long z = edge.minZ; z <= edge.maxZ; z++) {
                    add(rows, (int) z, new IntInterval(edge.x, edge.x));
                }
            }
        }

        List<Integer> intersections = new ArrayList<>(verticalEdges.size());
        for (long zValue = minZ; zValue <= maxZ; zValue++) {
            int z = (int) zValue;
            intersections.clear();

            for (VerticalEdge edge : verticalEdges) {
                // Lower endpoint inclusive, upper endpoint exclusive.
                if (edge.minZ <= z && z < edge.maxZ) {
                    intersections.add(edge.x);
                }
            }

            intersections.sort(Integer::compareTo);
            if ((intersections.size() & 1) != 0) {
                throw new IllegalArgumentException("Invalid polygon parity on row z=" + z);
            }

            for (int i = 0; i < intersections.size(); i += 2) {
                add(rows, z, new IntInterval(intersections.get(i), intersections.get(i + 1)));
            }
        }

        TreeMap<Integer, List<IntInterval>> merged = new TreeMap<>();
        for (Map.Entry<Integer, List<IntInterval>> row : rows.entrySet()) {
            row.getValue().sort(Comparator.comparingInt(IntInterval::minX)
                    .thenComparingInt(IntInterval::maxX));
            merged.put(row.getKey(), mergeIntervals(row.getValue()));
        }
        return new RasterizedSector(merged);
    }

    private static void add(Map<Integer, List<IntInterval>> rows, int z, IntInterval interval) {
        rows.computeIfAbsent(z, ignored -> new ArrayList<>()).add(interval);
    }

    private static List<IntInterval> mergeIntervals(List<IntInterval> sorted) {
        if (sorted.isEmpty()) {
            return List.of();
        }

        List<IntInterval> merged = new ArrayList<>();
        int currentMin = sorted.getFirst().minX();
        int currentMax = sorted.getFirst().maxX();

        for (int i = 1; i < sorted.size(); i++) {
            IntInterval next = sorted.get(i);
            if ((long) next.minX() <= (long) currentMax + 1L) {
                currentMax = Math.max(currentMax, next.maxX());
            } else {
                merged.add(new IntInterval(currentMin, currentMax));
                currentMin = next.minX();
                currentMax = next.maxX();
            }
        }
        merged.add(new IntInterval(currentMin, currentMax));
        return List.copyOf(merged);
    }

    private record VerticalEdge(int x, int minZ, int maxZ) { }
}

package com.gachicraft.ironfrontiers.mobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure-Java spawn formation generator for cluster sizes 1..10. */
public final class FormationCalculator {
    private static final double TWO_PI = Math.PI * 2.0;

    private FormationCalculator() { }

    public record Offset(double x, double z) { }

    public static List<Offset> calculate(int count, double configuredSpacing,
                                         double widestMobWidth, double jitter,
                                         boolean randomRotation, RandomGenerator random) {
        if (count < 1 || count > 10) throw new IllegalArgumentException("count must be 1..10");
        if (!Double.isFinite(configuredSpacing) || configuredSpacing < 0
                || !Double.isFinite(widestMobWidth) || widestMobWidth < 0
                || !Double.isFinite(jitter) || jitter < 0) {
            throw new IllegalArgumentException("formation settings must be finite and non-negative");
        }
        Objects.requireNonNull(random, "random");
        double spacing = Math.max(configuredSpacing, 1.5 * widestMobWidth);
        List<Offset> base = switch (count) {
            case 1 -> List.of(new Offset(0, 0));
            case 2 -> List.of(new Offset(-spacing / 2, 0), new Offset(spacing / 2, 0));
            case 3 -> regularPolygon(3, spacing / Math.sqrt(3.0), Math.toRadians(90));
            case 4 -> List.of(new Offset(-spacing / 2, -spacing / 2),
                    new Offset(spacing / 2, -spacing / 2),
                    new Offset(spacing / 2, spacing / 2),
                    new Offset(-spacing / 2, spacing / 2));
            case 5 -> regularPolygon(5, spacing / (2.0 * Math.sin(Math.toRadians(36))),
                    Math.toRadians(90));
            case 6 -> List.of(new Offset(-spacing, -spacing / 2),
                    new Offset(0, -spacing / 2), new Offset(spacing, -spacing / 2),
                    new Offset(-spacing, spacing / 2),
                    new Offset(0, spacing / 2), new Offset(spacing, spacing / 2));
            default -> poissonDisc(count, spacing, random);
        };

        double rotation = randomRotation ? random.nextDouble() * TWO_PI : 0.0;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double appliedJitter = count <= 6 ? jitter * 0.5 : jitter;
        List<Offset> result = new ArrayList<>(base.size());
        for (Offset point : base) {
            double x = point.x() * cos - point.z() * sin;
            double z = point.x() * sin + point.z() * cos;
            if (appliedJitter > 0) {
                x += random.nextDouble(-appliedJitter, appliedJitter);
                z += random.nextDouble(-appliedJitter, appliedJitter);
            }
            result.add(new Offset(x, z));
        }
        return List.copyOf(result);
    }

    private static List<Offset> regularPolygon(int count, double radius, double startAngle) {
        List<Offset> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = startAngle + TWO_PI * i / count;
            points.add(new Offset(Math.cos(angle) * radius, Math.sin(angle) * radius));
        }
        return points;
    }

    private static List<Offset> poissonDisc(int count, double spacing, RandomGenerator random) {
        double radius = spacing * Math.sqrt(count) * 0.9;
        double minimumSquared = spacing * spacing;
        List<Offset> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Offset accepted = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                double angle = random.nextDouble() * TWO_PI;
                double distance = Math.sqrt(random.nextDouble()) * radius;
                Offset candidate = new Offset(Math.cos(angle) * distance, Math.sin(angle) * distance);
                boolean separated = true;
                for (Offset point : points) {
                    double dx = point.x() - candidate.x();
                    double dz = point.z() - candidate.z();
                    if (dx * dx + dz * dz < minimumSquared) {
                        separated = false;
                        break;
                    }
                }
                if (separated) {
                    accepted = candidate;
                    break;
                }
            }
            if (accepted == null) {
                if (index == 0) accepted = new Offset(0, 0);
                else {
                    double angle = TWO_PI * (index - 1) / Math.max(1, count - 1);
                    accepted = new Offset(Math.cos(angle) * radius * 0.75,
                            Math.sin(angle) * radius * 0.75);
                }
            }
            points.add(accepted);
        }
        return List.copyOf(points);
    }
}

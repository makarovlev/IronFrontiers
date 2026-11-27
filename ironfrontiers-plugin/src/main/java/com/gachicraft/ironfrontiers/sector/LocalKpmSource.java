package com.gachicraft.ironfrontiers.sector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local KPM source; the cluster implementation is introduced in stage 6. */
public final class LocalKpmSource implements KpmSource {
    private final ConcurrentMap<String, Double> values = new ConcurrentHashMap<>();
    public void update(String sectorId, double kpm) {
        if (!Double.isFinite(kpm) || kpm < 0) throw new IllegalArgumentException("Invalid KPM");
        values.put(sectorId, kpm);
    }
    @Override public double kpm(String sectorId) { return values.getOrDefault(sectorId, 0.0); }
}

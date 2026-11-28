package com.gachicraft.ironfrontiers.mobs;

import java.util.*;

/** Immutable cluster lookup by case-insensitive id. */
public final class MobClusterRegistry {
    private final Map<String, MobCluster> clusters;

    public MobClusterRegistry(Collection<MobCluster> clusters) {
        Map<String, MobCluster> indexed = new LinkedHashMap<>();
        for (MobCluster cluster : clusters) {
            String key = cluster.id().toLowerCase(Locale.ROOT);
            if (indexed.putIfAbsent(key, cluster) != null) {
                throw new IllegalArgumentException("Duplicate mob cluster: " + cluster.id());
            }
        }
        this.clusters = Collections.unmodifiableMap(indexed);
    }

    public Optional<MobCluster> get(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(clusters.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<MobCluster> clusters() {
        return clusters.values();
    }

    public int size() {
        return clusters.size();
    }
}

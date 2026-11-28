package com.gachicraft.ironfrontiers.mobs;

import java.util.List;

/** Immutable, validated mob cluster. */
public record MobCluster(String id, List<MobEntry> entries, int totalAmount) {
    public MobCluster {
        if (id == null || id.isBlank() || entries == null || entries.isEmpty()
                || totalAmount < 1 || totalAmount > 10) {
            throw new IllegalArgumentException("Invalid cluster definition");
        }
        entries = List.copyOf(entries);
        int actual = entries.stream().mapToInt(MobEntry::amount).sum();
        if (actual != totalAmount) throw new IllegalArgumentException("Cluster amount mismatch");
    }
}

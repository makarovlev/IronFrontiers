package com.gachicraft.ironfrontiers.mobs;

import com.gachicraft.privatemobsx.api.MobType;

import java.util.Objects;

/** One weighted cluster entry. Amount is expanded into individual private mobs at spawn time. */
public record MobEntry(
        String entryId,
        MobType type,
        String mobId,
        int amount,
        int weightTenths,
        Integer levelMin,
        Object levelScale,
        Integer levelMax
) {
    public MobEntry {
        if (entryId == null || entryId.isBlank()
                || mobId == null || mobId.isBlank()
                || amount < 1 || amount > 10
                || weightTenths < 0) {
            throw new IllegalArgumentException("Invalid mob cluster entry");
        }
        Objects.requireNonNull(type, "type");
        if (type == MobType.MYTHICMOBS) {
            if (levelMin == null || levelMin < 1
                    || (levelMax != null && levelMax < levelMin)) {
                throw new IllegalArgumentException("Invalid MythicMobs level range");
            }
            if (levelScale != null && !(levelScale instanceof Number) && !(levelScale instanceof String)) {
                throw new IllegalArgumentException("level.scale must be a number or placeholder string");
            }
        }
    }

    public boolean hasLevelScale() {
        return levelScale != null;
    }
}

package com.gachicraft.privatemobsx.api;

import java.util.Objects;

/**
 * Immutable description of a mob to spawn.
 *
 * <p>For VANILLA, mobId is an EntityType name and level is ignored.
 * For MYTHICMOBS, mobId is the MythicMobs internal mob ID and level is the
 * requested Mythic level (defaults to 1 when null).</p>
 */
public final class MobSpec {

    private final MobType type;
    private final String mobId;
    private final Integer level;

    public MobSpec(MobType type, String mobId, Integer level) {
        this.type = Objects.requireNonNull(type, "type");
        if (mobId == null || mobId.isBlank()) {
            throw new IllegalArgumentException("mobId must not be blank");
        }
        if (type == MobType.MYTHICMOBS && level != null && level < 1) {
            throw new IllegalArgumentException("MythicMobs level must be at least 1");
        }
        this.mobId = mobId.trim();
        this.level = level;
    }

    public static MobSpec vanilla(String entityType) {
        return new MobSpec(MobType.VANILLA, entityType, null);
    }

    public static MobSpec mythic(String mythicMobId, Integer level) {
        return new MobSpec(MobType.MYTHICMOBS, mythicMobId, level);
    }

    public MobType getType() {
        return type;
    }

    public String getMobId() {
        return mobId;
    }

    public Integer getLevel() {
        return level;
    }
}

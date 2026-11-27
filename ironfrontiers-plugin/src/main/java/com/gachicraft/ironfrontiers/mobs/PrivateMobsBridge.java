package com.gachicraft.ironfrontiers.mobs;

import org.bukkit.Location;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal boundary for the PrivateMobsX SPI. The implementation module must
 * not access PrivateMobsX implementation classes directly.
 */
public interface PrivateMobsBridge {
    Optional<UUID> spawn(UUID owner, String mobType, String mobId, Integer level,
                         Location location, Map<String, String> tags);
    void despawn(UUID owner, UUID mobId, String reason);
    Collection<UUID> getMobIds(UUID owner, String tagKey, String tagValue);
    int removeAllOrphans();
    boolean isAvailable();
}

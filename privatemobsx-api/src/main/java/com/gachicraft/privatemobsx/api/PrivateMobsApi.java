package com.gachicraft.privatemobsx.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public integration contract exposed through Bukkit's ServicesManager.
 *
 * <p>Every method in this interface touches Bukkit state and MUST be called
 * from the server's primary thread. Returned handles and collections are safe
 * to retain and read asynchronously; their Bukkit Location values are
 * defensive snapshots.</p>
 */
public interface PrivateMobsApi {

    /**
     * Spawns and registers exactly one private mob for an online owner.
     * The target world chunk must already be loaded; this method never loads it.
     */
    SpawnResult spawn(UUID owner, MobSpec spec, Location location, Map<String, String> tags);

    /** Removes one tracked mob without firing death logic or producing drops/experience. */
    void despawn(PrivateMobHandle handle, DespawnReason reason);

    /** Removes all matching mobs owned by owner. */
    int despawnByTag(UUID owner, String tagKey, String tagValue, DespawnReason reason);

    /**
     * Removes at most maxPerCall matching mobs. Use this overload for
     * bounded work when an integration is cleaning up a large sector.
     */
    int despawnByTag(UUID owner, String tagKey, String tagValue,
                     DespawnReason reason, int maxPerCall);

    /** Returns an immutable snapshot of the owner's currently tracked mobs. */
    Collection<PrivateMobHandle> getMobs(UUID owner);

    /** Returns an immutable snapshot filtered by an exact tag key/value pair. */
    Collection<PrivateMobHandle> getMobs(UUID owner, String tagKey, String tagValue);

    boolean isPrivateMob(Entity entity);

    Optional<PrivateMobHandle> byEntity(Entity entity);

    /**
     * Removes private-mob entities left in loaded worlds but not associated with
     * this plugin instance's current in-memory registry (startup orphan sweep).
     */
    int removeAllOrphans();
}

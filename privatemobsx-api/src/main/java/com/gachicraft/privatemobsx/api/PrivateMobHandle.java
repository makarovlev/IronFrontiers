package com.gachicraft.privatemobsx.api;

import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;

/**
 * Thread-safe handle for one tracked mob.
 *
 * <p>Identity, owner, tags and the location snapshot never change. The location
 * accessor returns a defensive copy. Liveness is an atomic state updated by
 * PrivateMobsX, so it can safely be read from asynchronous integrations.</p>
 */
public interface PrivateMobHandle {

    /** UUID of the backing Bukkit entity (not its numeric entity ID). */
    UUID getMobId();

    UUID getOwner();

    Map<String, String> getTags();

    boolean isAlive();

    /** Returns a defensive copy of the last location snapshot. */
    Location location();
}

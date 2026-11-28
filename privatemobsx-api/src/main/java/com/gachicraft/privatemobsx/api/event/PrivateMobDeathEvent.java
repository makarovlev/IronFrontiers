package com.gachicraft.privatemobsx.api.event;

import com.gachicraft.privatemobsx.api.PrivateMobHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fired synchronously when a tracked entity dies, regardless of its killer. */
public final class PrivateMobDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PrivateMobHandle handle;
    private final UUID owner;
    private final Player killer;
    private final Map<String, String> tags;

    public PrivateMobDeathEvent(PrivateMobHandle handle, UUID owner,
                                Player killer, Map<String, String> tags) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.killer = killer;
        this.tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
    }

    public PrivateMobHandle getHandle() {
        return handle;
    }

    public UUID getOwner() {
        return owner;
    }

    /** Nullable when the death was not attributed to a player. */
    public Player getKiller() {
        return killer;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

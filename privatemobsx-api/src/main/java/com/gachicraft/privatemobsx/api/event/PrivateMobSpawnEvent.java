package com.gachicraft.privatemobsx.api.event;

import com.gachicraft.privatemobsx.api.PrivateMobHandle;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired synchronously on the primary thread after a private mob is registered. */
public final class PrivateMobSpawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final PrivateMobHandle handle;

    public PrivateMobSpawnEvent(PrivateMobHandle handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    public PrivateMobHandle getHandle() {
        return handle;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

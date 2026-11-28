package com.gachicraft.privatemobsx.api.event;

import com.gachicraft.privatemobsx.api.DespawnReason;
import com.gachicraft.privatemobsx.api.PrivateMobHandle;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired synchronously when a tracked mob is removed or naturally dies. */
public final class PrivateMobDespawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PrivateMobHandle handle;
    private final DespawnReason reason;

    public PrivateMobDespawnEvent(PrivateMobHandle handle, DespawnReason reason) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public PrivateMobHandle getHandle() {
        return handle;
    }

    public DespawnReason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

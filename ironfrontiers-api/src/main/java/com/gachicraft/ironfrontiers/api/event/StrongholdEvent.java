package com.gachicraft.ironfrontiers.api.event;

import com.gachicraft.ironfrontiers.api.StrongholdView;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/** Base event for stronghold transitions. */
@ApiStatus.Experimental
public abstract class StrongholdEvent extends Event {
    private final StrongholdView stronghold;

    protected StrongholdEvent(StrongholdView stronghold) {
        this.stronghold = Objects.requireNonNull(stronghold, "stronghold");
    }

    public final StrongholdView getStronghold() {
        return stronghold;
    }
}

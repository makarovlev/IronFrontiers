package com.gachicraft.ironfrontiers.api;

import org.jetbrains.annotations.ApiStatus;

/** Public stronghold lifecycle states. */
@ApiStatus.Experimental
public enum StrongholdState {
    NOT_BUILT,
    BUILDING,
    ACTIVE,
    BROKEN,
    REPAIRING
}

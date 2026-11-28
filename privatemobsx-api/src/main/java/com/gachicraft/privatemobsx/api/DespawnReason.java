package com.gachicraft.privatemobsx.api;

/** Reason associated with an explicit or lifecycle-driven private-mob removal. */
public enum DespawnReason {
    DEATH,
    API,
    OWNER_QUIT,
    WORLD_CHANGE,
    UNLOAD,
    PLUGIN_DISABLE
}

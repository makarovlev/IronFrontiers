package com.gachicraft.privatemobsx.api;

/** Stable error codes returned by PrivateMobsApi.spawn. */
public enum SpawnFailureReason {
    INVALID_MOB,
    CHUNK_NOT_LOADED,
    OWNER_OFFLINE,
    LIMIT_REACHED,
    ERROR
}

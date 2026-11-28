package com.gachicraft.ironfrontiers.mobs;

/** Boundary to the stage-three spawn director; the state machine only pauses new spawns. */
public interface MobSpawn {
    void pauseNewSpawns(String sectorId, int durationSeconds);

    /** Reloads mob cluster definitions after mobs.yml has been re-read. */
    default void reloadConfiguration() { }
}

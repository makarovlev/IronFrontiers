package com.gachicraft.ironfrontiers.sector;
/** Broadcasts durable global state changes to other servers. */
public interface StateBroadcaster { void sectorStateChanged(String sectorId, long version); }

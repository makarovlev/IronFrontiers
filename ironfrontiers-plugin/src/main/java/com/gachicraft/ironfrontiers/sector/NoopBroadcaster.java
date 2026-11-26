package com.gachicraft.ironfrontiers.sector;
/** No-op broadcaster used in single-server mode. */
public final class NoopBroadcaster implements StateBroadcaster {
    @Override public void sectorStateChanged(String sectorId, long version) { }
}

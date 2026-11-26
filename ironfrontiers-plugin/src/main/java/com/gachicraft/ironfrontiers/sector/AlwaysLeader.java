package com.gachicraft.ironfrontiers.sector;
/** Single-server authority implementation. */
public final class AlwaysLeader implements Authority { @Override public boolean isLeader() { return true; } }

package com.gachicraft.ironfrontiers.api;
import org.jetbrains.annotations.ApiStatus;
/** Immutable inclusive XZ bounding box. */
@ApiStatus.Experimental
public record SectorBounds(int minX, int minZ, int maxX, int maxZ) {
  public SectorBounds { if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid sector bounds"); }
}

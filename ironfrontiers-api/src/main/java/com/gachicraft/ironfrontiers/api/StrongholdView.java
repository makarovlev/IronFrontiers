package com.gachicraft.ironfrontiers.api;
import org.jetbrains.annotations.ApiStatus;
/** Immutable snapshot of a sector stronghold. */
@ApiStatus.Experimental
public record StrongholdView(String sectorId, StrongholdState state, int progressPercent) {
  public StrongholdView {
    if (sectorId == null || state == null || progressPercent < 0 || progressPercent > 100)
      throw new IllegalArgumentException("Invalid stronghold snapshot");
  }
}

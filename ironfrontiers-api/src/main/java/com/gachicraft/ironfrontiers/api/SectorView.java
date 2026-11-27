package com.gachicraft.ironfrontiers.api;
import org.jetbrains.annotations.ApiStatus;
import java.util.UUID;
/** Immutable, thread-safe snapshot of a sector. */
@ApiStatus.Experimental
public record SectorView(String id, String displayName, String worldName, UUID worldId,
    SectorState state, double kpm, double kpmCapture, double kpmHeld,
    long timeLeftSeconds, long area, SectorBounds boundingBox, StrongholdState strongholdState) {
  public SectorView {
    if (id == null || displayName == null || state == null || boundingBox == null)
      throw new IllegalArgumentException("SectorView required fields must not be null");
  }
}

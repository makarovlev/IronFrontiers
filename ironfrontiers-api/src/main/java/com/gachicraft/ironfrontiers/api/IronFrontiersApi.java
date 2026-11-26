package com.gachicraft.ironfrontiers.api;
import org.bukkit.Location;
import org.jetbrains.annotations.ApiStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
/**
 * Public read-only API. Implementations return immutable snapshots and are safe to read
 * from other threads; Bukkit objects are accepted only as query input.
 */
@ApiStatus.Experimental
public interface IronFrontiersApi {
  Optional<SectorView> getSector(String id);
  Collection<SectorView> getSectors();
  Optional<SectorView> getSectorAt(Location location);
  Optional<StrongholdView> getStronghold(String sectorId);
  double getPlayerKpm(UUID playerId, String sectorId);
}

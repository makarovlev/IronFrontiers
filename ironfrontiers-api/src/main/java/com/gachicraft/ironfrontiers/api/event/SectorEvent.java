package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.SectorView;
import org.bukkit.event.Event;
import java.util.Objects;
/** Base event for sector transitions. */
public abstract class SectorEvent extends Event {
  private final SectorView sector;
  protected SectorEvent(SectorView sector) { this.sector = Objects.requireNonNull(sector, "sector"); }
  public final SectorView getSector() { return sector; }
}

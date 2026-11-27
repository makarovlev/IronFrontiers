package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.SectorState;
import com.gachicraft.ironfrontiers.api.SectorView;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
@ApiStatus.Experimental
public final class SectorStateChangeEvent extends SectorEvent {
  private static final HandlerList HANDLERS = new HandlerList();
  private final SectorState previous;
  private final SectorState current;
  private final String reason;
  public SectorStateChangeEvent(SectorView sector, SectorState previous, SectorState current, String reason) {
    super(sector); this.previous = previous; this.current = current; this.reason = reason;
  }
  public SectorState getPrevious() { return previous; }
  public SectorState getCurrent() { return current; }
  public String getReason() { return reason; }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

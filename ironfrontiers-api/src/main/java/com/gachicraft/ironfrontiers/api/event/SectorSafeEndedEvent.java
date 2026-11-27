package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.SectorView;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
/** Fired on the corresponding sector lifecycle transition. */
@ApiStatus.Experimental
public final class SectorSafeEndedEvent extends SectorEvent {
  private static final HandlerList HANDLERS = new HandlerList();
  public SectorSafeEndedEvent(SectorView sector) { super(sector); }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

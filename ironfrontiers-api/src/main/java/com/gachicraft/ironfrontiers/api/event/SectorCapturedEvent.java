package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.SectorView;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
/** Fired when a sector is captured. */
@ApiStatus.Experimental
public final class SectorCapturedEvent extends SectorEvent implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private boolean cancelled;
  public SectorCapturedEvent(SectorView sector) { super(sector); }
  @Override public boolean isCancelled() { return cancelled; }
  @Override public void setCancelled(boolean value) { cancelled = value; }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

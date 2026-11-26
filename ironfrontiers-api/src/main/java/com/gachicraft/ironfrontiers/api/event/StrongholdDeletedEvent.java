package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.StrongholdView;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
/** Fired after the corresponding stronghold transition has been persisted. */
@ApiStatus.Experimental
public final class StrongholdDeletedEvent extends StrongholdEvent {
  private static final HandlerList HANDLERS = new HandlerList();
  public StrongholdDeletedEvent(StrongholdView stronghold) { super(stronghold); }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

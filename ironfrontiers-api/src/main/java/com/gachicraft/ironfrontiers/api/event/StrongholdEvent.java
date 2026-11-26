package com.gachicraft.ironfrontiers.api.event;
import com.gachicraft.ironfrontiers.api.StrongholdView;
import org.bukkit.event.Event;
import java.util.Objects;
/** Base event for stronghold transitions. */
public abstract class StrongholdEvent extends Event {
  private final StrongholdView stronghold;
  protected StrongholdEvent(StrongholdView stronghold) { this.stronghold = Objects.requireNonNull(stronghold, "stronghold"); }
  public final StrongholdView getStronghold() { return stronghold; }
}

package com.gachicraft.ironfrontiers.api.event;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import java.util.UUID;
/** Optional event for integrations that enable kill-event publication. */
@ApiStatus.Experimental
public final class KillCountedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID playerId;
  private final String sectorId;
  private final double weight;
  public KillCountedEvent(UUID playerId, String sectorId, double weight) {
    this.playerId = playerId; this.sectorId = sectorId; this.weight = weight;
  }
  public UUID getPlayerId() { return playerId; }
  public String getSectorId() { return sectorId; }
  public double getWeight() { return weight; }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

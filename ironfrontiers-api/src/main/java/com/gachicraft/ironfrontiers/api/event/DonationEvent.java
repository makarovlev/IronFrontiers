package com.gachicraft.ironfrontiers.api.event;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import java.util.Map;
import java.util.UUID;
/** Fired after a donation has been accepted and persisted. */
@ApiStatus.Experimental
public final class DonationEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID playerId;
  private final String sectorId;
  private final Map<String, Integer> acceptedByRequirement;
  public DonationEvent(UUID playerId, String sectorId, Map<String, Integer> acceptedByRequirement) {
    this.playerId = playerId; this.sectorId = sectorId; this.acceptedByRequirement = Map.copyOf(acceptedByRequirement);
  }
  public UUID getPlayerId() { return playerId; }
  public String getSectorId() { return sectorId; }
  public Map<String, Integer> getAcceptedByRequirement() { return acceptedByRequirement; }
  @Override public HandlerList getHandlers() { return HANDLERS; }
  public static HandlerList getHandlerList() { return HANDLERS; }
}

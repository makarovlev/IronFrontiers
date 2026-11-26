package com.gachicraft.ironfrontiers.mobs;

import org.bukkit.Location;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only fake for exercising spawn-session logic without PrivateMobsX. */
final class FakePrivateMobsBridge implements PrivateMobsBridge {
    private final Map<UUID, Map<UUID, Map<String, String>>> mobs = new ConcurrentHashMap<>();

    @Override
    public Optional<UUID> spawn(UUID owner, String mobType, String mobId, Integer level,
                                Location location, Map<String, String> tags) {
        UUID handle = UUID.randomUUID();
        mobs.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>()).put(handle, Map.copyOf(tags));
        return Optional.of(handle);
    }

    @Override
    public void despawn(UUID owner, UUID mobId, String reason) {
        Map<UUID, Map<String, String>> owned = mobs.get(owner);
        if (owned != null) owned.remove(mobId);
    }

    @Override
    public Collection<UUID> getMobIds(UUID owner, String tagKey, String tagValue) {
        Map<UUID, Map<String, String>> owned = mobs.getOrDefault(owner, Map.of());
        return owned.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue().get(tagKey), tagValue))
                .map(Map.Entry::getKey).toList();
    }

    @Override public int removeAllOrphans() { int size = mobs.values().stream().mapToInt(Map::size).sum(); mobs.clear(); return size; }
    @Override public boolean isAvailable() { return true; }
}

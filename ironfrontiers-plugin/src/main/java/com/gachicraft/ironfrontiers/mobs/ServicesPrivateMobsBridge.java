package com.gachicraft.ironfrontiers.mobs;

import com.gachicraft.privatemobsx.api.DespawnReason;
import com.gachicraft.privatemobsx.api.MobSpec;
import com.gachicraft.privatemobsx.api.MobType;
import com.gachicraft.privatemobsx.api.PrivateMobHandle;
import com.gachicraft.privatemobsx.api.PrivateMobsApi;
import com.gachicraft.privatemobsx.api.SpawnResult;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PrivateMobsX public ServicesManager adapter. Never touches implementation classes. */
public final class ServicesPrivateMobsBridge implements PrivateMobsBridge {
    private final JavaPlugin plugin;

    public ServicesPrivateMobsBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private PrivateMobsApi api() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PrivateMobsX")) return null;
        return plugin.getServer().getServicesManager().load(PrivateMobsApi.class);
    }

    private void requirePrimaryThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("PrivateMobsX API must only be called on the server thread");
        }
    }

    @Override
    public Optional<UUID> spawn(UUID owner, String mobType, String mobId, Integer level,
                                Location location, Map<String, String> tags) {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        if (api == null) return Optional.empty();
        MobType type;
        try {
            type = MobType.valueOf(mobType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        SpawnResult result = api.spawn(owner, new MobSpec(type, mobId, type == MobType.VANILLA ? null : level),
                location, tags);
        return result.isSuccess()
                ? result.getHandle().map(PrivateMobHandle::getMobId)
                : Optional.empty();
    }

    @Override
    public void despawn(UUID owner, UUID mobId, String reason) {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        if (api == null) return;
        DespawnReason despawnReason = reason(reason);
        for (PrivateMobHandle handle : api.getMobs(owner)) {
            if (handle.getMobId().equals(mobId)) {
                api.despawn(handle, despawnReason);
                return;
            }
        }
    }

    @Override
    public Collection<UUID> getMobIds(UUID owner, String tagKey, String tagValue) {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        if (api == null) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (PrivateMobHandle handle : api.getMobs(owner, tagKey, tagValue)) {
            ids.add(handle.getMobId());
        }
        return List.copyOf(ids);
    }

    @Override
    public int removeAllOrphans() {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        return api == null ? 0 : api.removeAllOrphans();
    }

    @Override
    public boolean isAvailable() {
        return api() != null;
    }

    public int despawnByTag(UUID owner, String key, String value, String reason, int maxPerCall) {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        return api == null ? 0 : api.despawnByTag(owner, key, value, reason(reason), maxPerCall);
    }

    public Collection<PrivateMobHandle> getHandles(UUID owner, String key, String value) {
        requirePrimaryThread();
        PrivateMobsApi api = api();
        return api == null ? List.of() : api.getMobs(owner, key, value);
    }

    private static DespawnReason reason(String value) {
        if (value == null) return DespawnReason.API;
        try {
            return DespawnReason.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return DespawnReason.API;
        }
    }
}

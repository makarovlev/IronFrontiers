package com.gachicraft.ironfrontiers.kpm;

import com.gachicraft.ironfrontiers.sector.KpmSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main-thread writer for sector and personal sliding windows. Readers consume
 * immutable snapshots published through AtomicReference, without touching windows.
 */
public final class KpmTracker implements KpmSource {
    private final int windowSeconds;
    private final int playerIdleCleanupSeconds;
    private final Map<String, KpmWindow> sectorWindows = new LinkedHashMap<>();
    private final Map<UUID, Map<String, PersonalWindow>> personalWindows = new HashMap<>();
    private final AtomicReference<Map<String, SectorKpmSnapshot>> sectorSnapshots =
            new AtomicReference<>(Map.of());
    private final AtomicReference<PersonalKpmSnapshot> personalSnapshot =
            new AtomicReference<>(new PersonalKpmSnapshot(0, Map.of(), Map.of()));

    public KpmTracker(Collection<String> sectorIds, int windowSeconds, int playerIdleCleanupSeconds) {
        if (windowSeconds < 1 || playerIdleCleanupSeconds < 1) {
            throw new IllegalArgumentException("KPM durations must be positive");
        }
        this.windowSeconds = windowSeconds;
        this.playerIdleCleanupSeconds = playerIdleCleanupSeconds;
        for (String id : sectorIds) {
            String key = normalize(id);
            if (sectorWindows.putIfAbsent(key, new KpmWindow(windowSeconds)) != null) {
                throw new IllegalArgumentException("Duplicate KPM sector id: " + id);
            }
        }
    }

    /** Records a real owner kill and its weighted contribution to sector and player KPM. */
    public void recordKill(String sectorId, UUID playerId, int weightTenths, long nowSecond) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (weightTenths <= 0) {
            throw new IllegalArgumentException("Kill weight must be positive");
        }
        KpmWindow sector = requireSector(sectorId);
        sector.add(weightTenths, nowSecond);

        Map<String, PersonalWindow> bySector = personalWindows.computeIfAbsent(
                playerId, ignored -> new HashMap<>());
        String key = normalize(sectorId);
        PersonalWindow personal = bySector.get(key);
        if (personal == null) {
            personal = new PersonalWindow(new KpmWindow(windowSeconds), nowSecond);
            bySector.put(key, personal);
        }
        personal.window.add(weightTenths, nowSecond);
        personal.lastKillSecond = nowSecond;
    }

    /** Records a debug kill without attributing the contribution to a player. */
    public void recordDebugKill(String sectorId, int weightTenths, long nowSecond) {
        if (weightTenths <= 0) {
            throw new IllegalArgumentException("Kill weight must be positive");
        }
        requireSector(sectorId).add(weightTenths, nowSecond);
    }

    /**
     * Advances every window, expires idle personal windows and atomically publishes
     * new snapshots. Call at kpm.update_interval_seconds.
     */
    public void tick(long nowSecond) {
        Map<String, SectorKpmSnapshot> nextSectors = new LinkedHashMap<>();
        for (Map.Entry<String, KpmWindow> entry : sectorWindows.entrySet()) {
            double value = entry.getValue().sum(nowSecond);
            nextSectors.put(entry.getKey(),
                    new SectorKpmSnapshot(entry.getKey(), value, nowSecond));
        }
        sectorSnapshots.set(Collections.unmodifiableMap(nextSectors));

        Map<PersonalKpmSnapshot.PlayerSectorKey, Double> nextPlayers = new HashMap<>();
        Map<UUID, Double> nextTotals = new HashMap<>();
        Iterator<Map.Entry<UUID, Map<String, PersonalWindow>>> playerIterator =
                personalWindows.entrySet().iterator();

        while (playerIterator.hasNext()) {
            Map.Entry<UUID, Map<String, PersonalWindow>> playerEntry = playerIterator.next();
            UUID playerId = playerEntry.getKey();
            Map<String, PersonalWindow> bySector = playerEntry.getValue();
            Iterator<Map.Entry<String, PersonalWindow>> sectorIterator = bySector.entrySet().iterator();
            double total = 0.0;

            while (sectorIterator.hasNext()) {
                Map.Entry<String, PersonalWindow> personalEntry = sectorIterator.next();
                PersonalWindow window = personalEntry.getValue();
                if (nowSecond - window.lastKillSecond >= playerIdleCleanupSeconds) {
                    sectorIterator.remove();
                    continue;
                }

                double value = window.window.sum(nowSecond);
                if (value > 0) {
                    nextPlayers.put(new PersonalKpmSnapshot.PlayerSectorKey(
                            playerId, personalEntry.getKey()), value);
                    total += value;
                }
            }

            if (bySector.isEmpty()) {
                playerIterator.remove();
            } else if (total > 0) {
                nextTotals.put(playerId, total);
            }
        }

        personalSnapshot.set(new PersonalKpmSnapshot(nowSecond, nextPlayers, nextTotals));
    }

    public Map<String, SectorKpmSnapshot> snapshots() {
        return sectorSnapshots.get();
    }

    public Optional<SectorKpmSnapshot> snapshot(String sectorId) {
        return Optional.ofNullable(sectorSnapshots.get().get(normalize(sectorId)));
    }

    public PersonalKpmSnapshot personalSnapshot() {
        return personalSnapshot.get();
    }

    public double playerKpm(UUID playerId, String sectorId) {
        return personalSnapshot.get().playerKpm(playerId, normalize(sectorId));
    }

    public double playerKpmSum(UUID playerId) {
        return personalSnapshot.get().playerKpmSum(playerId);
    }

    @Override
    public double kpm(String sectorId) {
        SectorKpmSnapshot snapshot = sectorSnapshots.get().get(normalize(sectorId));
        return snapshot == null ? 0.0 : snapshot.kpm();
    }

    private KpmWindow requireSector(String sectorId) {
        KpmWindow window = sectorWindows.get(normalize(sectorId));
        if (window == null) {
            throw new IllegalArgumentException("Unknown sector for KPM: " + sectorId);
        }
        return window;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sector id must not be blank");
        }
        return id.toLowerCase(Locale.ROOT);
    }

    private static final class PersonalWindow {
        private final KpmWindow window;
        private long lastKillSecond;

        private PersonalWindow(KpmWindow window, long lastKillSecond) {
            this.window = window;
            this.lastKillSecond = lastKillSecond;
        }
    }
}

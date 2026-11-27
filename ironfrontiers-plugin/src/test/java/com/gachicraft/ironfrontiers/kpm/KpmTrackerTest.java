package com.gachicraft.ironfrontiers.kpm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KpmTrackerTest {
    @Test
    void aggregatesKillsFromAllPlayersAndPublishesPersonalSnapshots() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        KpmTracker tracker = new KpmTracker(List.of("aa01"), 60, 120);
        tracker.recordKill("AA01", alice, 10, 100);
        tracker.recordKill("aa01", bob, 15, 100);
        tracker.recordKill("aa01", alice, 5, 101);
        tracker.tick(101);

        assertEquals(3.0, tracker.kpm("aa01"), 0.00001);
        assertEquals(1.5, tracker.playerKpm(alice, "aa01"), 0.00001);
        assertEquals(1.5, tracker.playerKpmSum(alice), 0.00001);
        assertEquals(1.5, tracker.playerKpm(bob, "aa01"), 0.00001);
    }

    @Test
    void personalWindowsAreCreatedLazilyAndRemovedAfterIdleTimeout() {
        UUID player = UUID.randomUUID();
        KpmTracker tracker = new KpmTracker(List.of("aa01"), 60, 10);
        assertEquals(0.0, tracker.playerKpm(player, "aa01"));
        tracker.recordKill("aa01", player, 10, 100);
        tracker.tick(109);
        assertEquals(1.0, tracker.playerKpm(player, "aa01"));
        tracker.tick(110);
        assertEquals(0.0, tracker.playerKpm(player, "aa01"));
    }

    @Test
    void debugKillsAffectSectorButDoNotCreatePersonalWindows() {
        UUID player = UUID.randomUUID();
        KpmTracker tracker = new KpmTracker(List.of("aa01"), 60, 120);
        tracker.recordDebugKill("aa01", 20, 10);
        tracker.tick(10);

        assertEquals(2.0, tracker.kpm("aa01"));
        assertEquals(0.0, tracker.playerKpmSum(player));
    }

    @Test
    void publishesImmutableSnapshots() {
        KpmTracker tracker = new KpmTracker(List.of("aa01"), 60, 120);
        tracker.tick(100);
        assertThrows(UnsupportedOperationException.class,
                () -> tracker.snapshots().put("bb02", new SectorKpmSnapshot("bb02", 1.0, 100)));
    }
}

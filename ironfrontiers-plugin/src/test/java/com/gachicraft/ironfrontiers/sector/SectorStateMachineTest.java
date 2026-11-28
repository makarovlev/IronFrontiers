package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.api.SectorState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectorStateMachineTest {
    private static SectorStatePolicy policy(boolean freezeWhenEmpty, boolean infiniteSafe) {
        return new SectorStatePolicy(450, 230, 10, 5, 30,
                3, 10, 8, infiniteSafe, freezeWhenEmpty, false,
                2.0, 5.0, 0.9);
    }

    @Test
    void enemyCapturesWhenCaptureThresholdIsMetEvenDuringStartupGrace() {
        SectorRuntimeState enemy = SectorRuntimeState.enemy();
        var result = SectorStateMachine.tick(enemy, policy(false, false), 450, 1, true, 0);

        assertEquals(SectorState.SAFE, result.state().state());
        assertEquals(10L, result.state().stateLeftSeconds());
        assertEquals(8L, result.state().spawnPauseLeftSeconds());
        assertEquals(SectorTransition.Kind.CAPTURED, result.transition().kind());
        assertEquals(1L, result.state().version());
    }

    @Test
    void occupationBlocksRecaptureUntilAFollowingTick() {
        SectorRuntimeState enemy = new SectorRuntimeState(SectorState.ENEMY, null, 1,
                null, 0, 0, 0, 4, false);
        var first = SectorStateMachine.tick(enemy, policy(false, false), 900, 1, false, 1);
        assertEquals(0L, first.state().occupationLeftSeconds());
        assertEquals(SectorState.ENEMY, first.state().state());

        var second = SectorStateMachine.tick(first.state(), policy(false, false), 900, 1, false, 1);
        assertEquals(SectorState.SAFE, second.state().state());
    }

    @Test
    void safeEndsToHeldWhenHeldThresholdIsMet() {
        SectorRuntimeState safe = new SectorRuntimeState(SectorState.SAFE, 1L, 0,
                null, 0, 0, 0, 2, false);
        var result = SectorStateMachine.tick(safe, policy(false, false), 230, 1, false, 1);
        assertEquals(SectorState.HELD, result.state().state());
        assertEquals(SectorTransition.Kind.SAFE_ENDED_TO_HELD, result.transition().kind());
        assertEquals(3L, result.state().version());
    }

    @Test
    void safeEndsToDestructionWhenHeldThresholdIsNotMet() {
        SectorRuntimeState safe = new SectorRuntimeState(SectorState.SAFE, 1L, 0,
                null, 0, 0, 0, 2, false);
        var result = SectorStateMachine.tick(safe, policy(false, false), 229, 1, false, 1);
        assertEquals(SectorState.DESTRUCTION, result.state().state());
        assertEquals(5L, result.state().stateLeftSeconds());
        assertEquals(SectorTransition.Kind.DESTRUCTION_STARTED, result.transition().kind());
    }

    @Test
    void destructionNeedsConsecutiveHeldTimeToCancel() {
        SectorRuntimeState destruction = new SectorRuntimeState(SectorState.DESTRUCTION, 10L, 0,
                null, 0, 0, 0, 7, false);
        var first = SectorStateMachine.tick(destruction, policy(false, false), 230, 1, false, 1);
        assertEquals(SectorState.DESTRUCTION, first.state().state());
        assertEquals(1L, first.state().heldOkSeconds());
        assertEquals(9L, first.state().stateLeftSeconds());

        var dip = SectorStateMachine.tick(first.state(), policy(false, false), 229, 1, false, 1);
        assertEquals(0L, dip.state().heldOkSeconds());

        var regain1 = SectorStateMachine.tick(dip.state(), policy(false, false), 230, 1, false, 1);
        var regain2 = SectorStateMachine.tick(regain1.state(), policy(false, false), 230, 1, false, 1);
        var regain3 = SectorStateMachine.tick(regain2.state(), policy(false, false), 230, 1, false, 1);
        assertEquals(SectorState.HELD, regain3.state().state());
        assertEquals(SectorTransition.Kind.DESTRUCTION_CANCELLED, regain3.transition().kind());
        assertEquals(6L, regain3.state().destructionSavedSeconds());
        assertEquals(10L, regain3.state().resumeWindowLeftSeconds());
    }

    @Test
    void reenteringDestructionWithinResumeWindowContinuesSavedRemainder() {
        SectorRuntimeState held = new SectorRuntimeState(SectorState.HELD, null, 0,
                3L, 10, 0, 0, 8, false);
        var result = SectorStateMachine.tick(held, policy(false, false), 229, 1, false, 1);
        assertEquals(SectorState.DESTRUCTION, result.state().state());
        assertEquals(3L, result.state().stateLeftSeconds());
        assertEquals(9L, result.state().resumeWindowLeftSeconds());
    }

    @Test
    void resumeWindowFreezesWhenEmptyAndReusesSavedDestructionRemainder() {
        SectorRuntimeState held = new SectorRuntimeState(SectorState.HELD, null, 0,
                4L, 10, 0, 0, 5, false);

        var frozen = SectorStateMachine.tick(held, policy(true, false),
                229, 5, false, 0);
        assertEquals(SectorState.HELD, frozen.state().state());
        assertEquals(4L, frozen.state().destructionSavedSeconds());
        assertEquals(10L, frozen.state().resumeWindowLeftSeconds());

        var resumed = SectorStateMachine.tick(frozen.state(), policy(true, false),
                229, 5, false, 1);
        assertEquals(SectorState.DESTRUCTION, resumed.state().state());
        assertEquals(4L, resumed.state().stateLeftSeconds());
        assertEquals(5L, resumed.state().resumeWindowLeftSeconds());
    }

    @Test
    void newCaptureClearsAnyOldSavedDestructionRemainder() {
        SectorRuntimeState enemy = new SectorRuntimeState(SectorState.ENEMY, null, 0,
                7L, 50, 0, 0, 11, false);
        var captured = SectorStateMachine.tick(enemy, policy(false, false),
                450, 1, false, 1);

        assertEquals(SectorState.SAFE, captured.state().state());
        assertNull(captured.state().destructionSavedSeconds());
        assertEquals(0L, captured.state().resumeWindowLeftSeconds());
        assertEquals(12L, captured.state().version());
    }

    @Test
    void safeTimerAndOccupationFreezeWhenServerIsEmpty() {
        SectorRuntimeState safe = new SectorRuntimeState(SectorState.SAFE, 7L, 0,
                null, 0, 0, 0, 1, false);
        SectorRuntimeState enemy = new SectorRuntimeState(SectorState.ENEMY, null, 7,
                null, 0, 0, 1, 0, false);

        var safeResult = SectorStateMachine.tick(safe, policy(true, false), 0, 5, false, 0);
        var enemyResult = SectorStateMachine.tick(enemy, policy(true, false), 0, 5, false, 0);

        assertEquals(7L, safeResult.state().stateLeftSeconds());
        assertEquals(7L, enemyResult.state().occupationLeftSeconds());
    }

    @Test
    void startupAndFreezeWhenEmptyPauseOnlyLossPaths() {
        SectorRuntimeState destruction = new SectorRuntimeState(SectorState.DESTRUCTION, 5L, 12,
                null, 0, 0, 0, 3, false);

        var startup = SectorStateMachine.tick(destruction, policy(false, false), 0, 1, true, 1);
        assertEquals(5L, startup.state().stateLeftSeconds());
        assertEquals(11L, startup.state().occupationLeftSeconds());

        var empty = SectorStateMachine.tick(destruction, policy(true, false), 0, 1, false, 0);
        assertEquals(5L, empty.state().stateLeftSeconds());
        assertEquals(12L, empty.state().occupationLeftSeconds());
    }

    @Test
    void infiniteSafeNeverCountsDown() {
        SectorRuntimeState safe = new SectorRuntimeState(SectorState.SAFE, 1L, 0,
                null, 0, 0, 0, 2, false);
        var result = SectorStateMachine.tick(safe, policy(false, true), 0, 10, false, 1);
        assertEquals(SectorState.SAFE, result.state().state());
        assertEquals(1L, result.state().stateLeftSeconds());
        assertNull(result.transition());
    }

    @Test
    void destructionExpiryLosesSectorAndStartsOccupation() {
        SectorRuntimeState destruction = new SectorRuntimeState(SectorState.DESTRUCTION, 1L, 0,
                null, 0, 0, 0, 9, false);
        var result = SectorStateMachine.tick(destruction, policy(false, false), 0, 1, false, 1);
        assertEquals(SectorState.ENEMY, result.state().state());
        assertEquals(30L, result.state().occupationLeftSeconds());
        assertEquals(SectorTransition.Kind.LOST, result.transition().kind());
    }

    @Test
    void heldEffectiveThresholdUsesStrongholdMultiplierRoundedToTenths() {
        SectorStatePolicy withStronghold = new SectorStatePolicy(450, 255, 10, 5, 30,
                3, 10, 8, false, false, true, 2.0, 5.0, 0.9);
        assertEquals(230, withStronghold.effectiveHeldTenths());
        assertEquals(20L, withStronghold.effectiveSafeTimeSeconds());
        assertEquals(25L, withStronghold.effectiveDestructionTimeSeconds());
    }
}

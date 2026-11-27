package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.api.SectorState;

import java.util.Objects;

/**
 * Pure Java sector state automaton. It knows nothing about Bukkit, persistence,
 * worlds or players; the controller supplies snapshots and elapsed seconds.
 */
public final class SectorStateMachine {
    private SectorStateMachine() { }

    public static StepResult tick(SectorRuntimeState current, SectorStatePolicy policy,
                                  int kpmTenths, int deltaSeconds, boolean startupFrozen,
                                  int onlinePlayers) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(policy, "policy");
        if (kpmTenths < 0 || deltaSeconds < 1 || onlinePlayers < 0) {
            throw new IllegalArgumentException("Invalid state-machine tick inputs");
        }
        if (current.initializing()) {
            return new StepResult(current, null);
        }

        boolean emptyFrozen = policy.freezeWhenEmpty() && onlinePlayers == 0;
        boolean frozenForLoss = startupFrozen || emptyFrozen;
        SectorRuntimeState next = tickPassiveTimers(current, deltaSeconds, emptyFrozen);
        SectorTransition.Kind transition = null;
        boolean returnedToHeld = false;

        switch (current.state()) {
            case ENEMY -> {
                // Occupation blocks recapture. It is frozen only by freeze_when_empty,
                // not by startup grace, as specified for the ENEMY branch.
                if (current.occupationLeftSeconds() > 0) {
                    long left = emptyFrozen ? current.occupationLeftSeconds()
                            : subtractToZero(current.occupationLeftSeconds(), deltaSeconds);
                    next = copy(next, next.state(), next.stateLeftSeconds(), left,
                            next.destructionSavedSeconds(), next.resumeWindowLeftSeconds(),
                            next.spawnPauseLeftSeconds(), next.heldOkSeconds(),
                            next.version(), next.initializing());
                } else if (kpmTenths >= policy.captureTenths()) {
                    next = capture(next, policy);
                    transition = SectorTransition.Kind.CAPTURED;
                }
            }
            case SAFE -> {
                if (!policy.infiniteSafe() && !frozenForLoss) {
                    long left = subtractToZero(requireTimer(current), deltaSeconds);
                    next = copy(next, next.state(), left, next.occupationLeftSeconds(),
                            next.destructionSavedSeconds(), next.resumeWindowLeftSeconds(),
                            next.spawnPauseLeftSeconds(), next.heldOkSeconds(),
                            next.version(), next.initializing());
                    if (left == 0) {
                        if (kpmTenths >= policy.effectiveHeldTenths()) {
                            next = changeState(next, SectorState.HELD, null,
                                    next.occupationLeftSeconds(), next.destructionSavedSeconds(),
                                    next.resumeWindowLeftSeconds(), next.spawnPauseLeftSeconds(),
                                    0, next.version() + 1);
                            transition = SectorTransition.Kind.SAFE_ENDED_TO_HELD;
                        } else {
                            next = startDestruction(next, policy);
                            transition = SectorTransition.Kind.DESTRUCTION_STARTED;
                        }
                    }
                }
            }
            case HELD -> {
                if (!frozenForLoss && kpmTenths < policy.effectiveHeldTenths()) {
                    next = startDestruction(next, policy);
                    transition = SectorTransition.Kind.DESTRUCTION_STARTED;
                }
            }
            case DESTRUCTION -> {
                long heldOk = kpmTenths >= policy.effectiveHeldTenths()
                        ? Math.addExact(current.heldOkSeconds(), deltaSeconds) : 0L;
                next = copy(next, next.state(), next.stateLeftSeconds(),
                        next.occupationLeftSeconds(), next.destructionSavedSeconds(),
                        next.resumeWindowLeftSeconds(), next.spawnPauseLeftSeconds(),
                        heldOk, next.version(), next.initializing());

                if (heldOk >= policy.destructionCancelHoldSeconds()) {
                    next = returnToHeld(next, policy);
                    transition = SectorTransition.Kind.DESTRUCTION_CANCELLED;
                    returnedToHeld = true;
                } else if (!frozenForLoss) {
                    long left = subtractToZero(requireTimer(current), deltaSeconds);
                    next = copy(next, next.state(), left, next.occupationLeftSeconds(),
                            next.destructionSavedSeconds(), next.resumeWindowLeftSeconds(),
                            next.spawnPauseLeftSeconds(), heldOk,
                            next.version(), next.initializing());
                    if (left == 0) {
                        next = loseSector(next, policy);
                        transition = SectorTransition.Kind.LOST;
                    }
                }
            }
        }

        // A newly-created resume window begins after this tick, not before it.
        if (!returnedToHeld && !frozenForLoss && next.resumeWindowLeftSeconds() > 0) {
            long resumeLeft = subtractToZero(next.resumeWindowLeftSeconds(), deltaSeconds);
            next = copy(next, next.state(), next.stateLeftSeconds(), next.occupationLeftSeconds(),
                    resumeLeft == 0 ? null : next.destructionSavedSeconds(),
                    resumeLeft, next.spawnPauseLeftSeconds(), next.heldOkSeconds(),
                    next.version(), next.initializing());
        }

        if (transition != null) {
            return new StepResult(next, new SectorTransition(transition, current, next));
        }
        return new StepResult(next, null);
    }

    private static SectorRuntimeState tickPassiveTimers(SectorRuntimeState current,
                                                         int deltaSeconds, boolean emptyFrozen) {
        long occupation = current.occupationLeftSeconds();
        if (occupation > 0 && !emptyFrozen) {
            occupation = subtractToZero(occupation, deltaSeconds);
        }
        long spawnPause = subtractToZero(current.spawnPauseLeftSeconds(), deltaSeconds);
        return copy(current, current.state(), current.stateLeftSeconds(), occupation,
                current.destructionSavedSeconds(), current.resumeWindowLeftSeconds(),
                spawnPause, current.heldOkSeconds(), current.version(), current.initializing());
    }

    private static SectorRuntimeState capture(SectorRuntimeState current, SectorStatePolicy policy) {
        return changeState(current, SectorState.SAFE, policy.effectiveSafeTimeSeconds(),
                current.occupationLeftSeconds(), null, 0,
                policy.spawnPauseAfterCaptureSeconds(), 0, current.version() + 1);
    }

    private static SectorRuntimeState startDestruction(SectorRuntimeState current,
                                                       SectorStatePolicy policy) {
        long left = current.resumeWindowLeftSeconds() > 0
                && current.destructionSavedSeconds() != null
                ? current.destructionSavedSeconds()
                : policy.effectiveDestructionTimeSeconds();
        return changeState(current, SectorState.DESTRUCTION, left,
                current.occupationLeftSeconds(), current.destructionSavedSeconds(),
                current.resumeWindowLeftSeconds(), current.spawnPauseLeftSeconds(),
                0, current.version() + 1);
    }

    private static SectorRuntimeState returnToHeld(SectorRuntimeState current,
                                                   SectorStatePolicy policy) {
        return changeState(current, SectorState.HELD, null,
                current.occupationLeftSeconds(), requireTimer(current),
                policy.destructionResumeWindowSeconds(), current.spawnPauseLeftSeconds(),
                0, current.version() + 1);
    }

    private static SectorRuntimeState loseSector(SectorRuntimeState current,
                                                 SectorStatePolicy policy) {
        return changeState(current, SectorState.ENEMY, null,
                policy.occupationTimeSeconds(), null, 0,
                current.spawnPauseLeftSeconds(), 0, current.version() + 1);
    }

    private static SectorRuntimeState changeState(SectorRuntimeState current, SectorState state,
                                                  Long timer, long occupation, Long saved,
                                                  long resume, long spawnPause, long heldOk,
                                                  long version) {
        return new SectorRuntimeState(state, timer, occupation, saved,
                resume, spawnPause, heldOk, version, current.initializing());
    }

    private static SectorRuntimeState copy(SectorRuntimeState ignored, SectorState state,
                                           Long timer, long occupation, Long saved,
                                           long resume, long spawnPause, long heldOk,
                                           long version, boolean initializing) {
        return new SectorRuntimeState(state, timer, occupation, saved,
                resume, spawnPause, heldOk, version, initializing);
    }

    private static long requireTimer(SectorRuntimeState state) {
        Long timer = state.stateLeftSeconds();
        if (timer == null) {
            throw new IllegalStateException(state.state() + " has no timer");
        }
        return timer;
    }

    private static long subtractToZero(long current, int delta) {
        return current <= delta ? 0L : current - delta;
    }

    public record StepResult(SectorRuntimeState state, SectorTransition transition) {
        public StepResult {
            Objects.requireNonNull(state, "state");
        }

        public boolean transitioned() {
            return transition != null;
        }
    }
}

package ru.podyom.core

import org.junit.Assert.*
import org.junit.Test

class WakeRouteTest {
    private val run = AlarmRun("wake", AlarmPlan("alarm"), 1_000, state = RunState.WAKE_CHECK_ACTIVE,
        eventAt = 3_000, deadline = 3_000)

    @Test fun routeWaitsForRoomAndSeparatesMissingCancelledAndExpired() {
        val key = run.checkKey
        assertEquals(CheckPage.LOADING, checkPage(key, null, 2_000))
        assertEquals(CheckPage.MISSING, checkPage(key, emptyList(), 2_000))
        assertEquals(CheckPage.ACTIVE, checkPage(key, listOf(run), 2_999))
        assertEquals(CheckPage.EXPIRED, checkPage(key, listOf(run), 3_000))
        assertEquals(CheckPage.CANCELLED, checkPage(key, listOf(run.copy(state = RunState.CANCELLED)), 2_000))
        assertEquals(CheckPage.RE_RING, checkPage(key, listOf(run.copy(state = RunState.RE_RINGING, cycle = 1)), 3_001))
    }

    @Test fun oldRouteNeverSelectsAnotherRunsActiveCheckOrItsNewCycle() {
        val other = run.copy(id = "earlier", deadline = 2_500)
        val newerCycle = run.copy(cycle = 1)
        assertEquals(other, nextActiveCheck(listOf(run, other), 2_000))
        assertEquals(CheckPage.ACTIVE, checkPage(run.checkKey, listOf(other, run), 2_000))
        assertEquals(CheckPage.EXPIRED, checkPage(run.checkKey, listOf(newerCycle), 2_000))
        assertEquals(CheckPage.ACTIVE, checkPage(run.checkKey, listOf(run.copy(version = 4)), 2_000))
    }

    @Test fun intermediateConfirmationDoesNotFinishTheNextCheck() {
        val next = run.copy(state = RunState.WAITING_WAKE_CHECK, checkIndex = 1)
        assertEquals(CheckPage.CONFIRMED, checkPage(run.checkKey, listOf(next), 2_000))
        assertEquals(CheckPage.CHANGED, checkPage(next.checkKey, listOf(next), 2_000))
    }

    @Test fun staleRingBecomesMissedWithoutCreditingMission() {
        val ringing = run.copy(state = RunState.MISSION_RUNNING, ringingSince = 2_000, progress = 1)
        val cutoff = WakeMachine.ringExpiresAt(ringing)
        assertEquals(ringing, WakeMachine.expireRing(ringing, cutoff - 1))
        val missed = WakeMachine.expireRing(ringing, cutoff)
        assertTrue(missed.terminal)
        assertEquals(RunState.MISSED, missed.state)
        assertEquals(1, missed.progress)
        assertEquals(run, WakeMachine.expireRing(run, cutoff))
    }
}

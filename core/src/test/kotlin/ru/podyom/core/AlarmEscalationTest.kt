package ru.podyom.core

import org.junit.Assert.*
import org.junit.Test

class AlarmEscalationTest {
    @Test fun escalationIsOptInPerRingingOccurrenceAndStopsOnAnAnswer() {
        val run = AlarmRun("a", AlarmPlan("plan", escalationSeconds = 30), 0, state = RunState.RINGING)
        assertFalse(AlarmEscalation.due(run, 29_999))
        assertTrue(AlarmEscalation.due(run, 30_000))
        assertFalse(AlarmEscalation.due(run.copy(state = RunState.MISSION_RUNNING), 40_000))
        assertFalse(AlarmEscalation.due(run.copy(state = RunState.SNOOZED), 40_000))
        assertFalse(AlarmEscalation.due(run.copy(state = RunState.CANCELLED), 40_000))
        assertFalse(AlarmEscalation.due(run.copy(plan = AlarmPlan("old")), 900_000))
        assertFalse(AlarmEscalation.due(run.copy(id = "replacement"), 0))
    }

    @Test fun playerVolumeStaysWithinUserLimitDuringRampAndDucking() {
        val run = AlarmRun("a", AlarmPlan("plan", escalationSeconds = 30, volume = .4f, rampSeconds = 20), 0, state = RunState.RINGING)
        assertEquals(.2f, AlarmEscalation.playerVolume(run, 10_000), .001f)
        assertEquals(.4f, AlarmEscalation.playerVolume(run, 90_000), .001f)
        assertEquals(.1f, AlarmEscalation.playerVolume(run.copy(state = RunState.MISSION_RUNNING), 90_000), .001f)
    }
}

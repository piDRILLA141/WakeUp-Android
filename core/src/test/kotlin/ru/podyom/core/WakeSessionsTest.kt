package ru.podyom.core

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class WakeSessionsTest {
    private val start = Instant.parse("2026-11-26T06:00:00Z").toEpochMilli()
    private val earlier = AlarmRun("a", AlarmPlan("earlier"), start, state = RunState.MISSION_RUNNING, firedAt = start)
    private val later = AlarmRun("b", AlarmPlan("later"), start + 600_000, state = RunState.RINGING)
    private fun matches(a: AlarmRun, b: AlarmRun, enabled: Boolean = true) =
        WakeSessions.replaces(a, b, ZoneOffset.UTC, enabled)

    @Test fun replacesOnlyEarlierOrdinaryTasksWithinOneLocalSession() {
        assertTrue(matches(earlier, later))
        assertFalse(matches(later, earlier))
        assertFalse(matches(earlier, later, enabled = false))
        assertFalse(matches(earlier, later.copy(scheduledAt = start + 2 * WakeSessions.WINDOW_MS)))
        assertTrue(matches(earlier, later.copy(scheduledAt = start)))
        val nearMidnight = start + (17 * 60 + 55) * 60_000L
        assertFalse(matches(earlier.copy(scheduledAt = nearMidnight), later.copy(scheduledAt = nearMidnight + 600_000)))
    }

    @Test fun snoozeQuickIndependentAndAlreadyStartedOrdinaryChecksStayIntact() {
        assertFalse(matches(earlier.copy(snoozes = 1), later))
        assertFalse(matches(earlier.copy(state = RunState.SNOOZED), later))
        assertFalse(matches(earlier.copy(plan = earlier.plan.copy(quick = true)), later))
        assertFalse(matches(earlier, later.copy(plan = later.plan.copy(independent = true))))
        assertFalse(matches(earlier.copy(state = RunState.WAITING_WAKE_CHECK), later))
        assertFalse(matches(earlier.copy(cycle = 1), later))
        assertFalse(matches(earlier.copy(state = RunState.COMPLETED), later))
    }

    @Test fun relatedGroupCheckCanBeReplacedButOtherGroupsRemainIndependent() {
        val check = earlier.copy(state = RunState.WAKE_CHECK_ACTIVE, plan = earlier.plan.copy(groupId = "school"))
        assertFalse(matches(check, later))
        assertFalse(matches(check, later.copy(plan = later.plan.copy(groupId = "weekend"))))
        val backup = later.copy(plan = later.plan.copy(groupId = "school"))
        assertTrue(matches(check, backup, enabled = false))
        assertFalse(matches(check.copy(snoozes = 1), backup))
        val retired = WakeMachine.supersede(check, start)
        assertEquals(RunState.MISSED, retired.state)
        assertEquals(CheckPage.EXPIRED, checkPage(check.checkKey, listOf(retired), start))
    }
}

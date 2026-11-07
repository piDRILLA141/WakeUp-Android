package ru.podyom.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlinx.serialization.json.Json

class CoreTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun plan() = AlarmPlan("a", missions = listOf(MissionSpec(count = 1)), wakeDelaySeconds = 180)
    private fun run() = AlarmRun("r", plan(), 1000)
    @Test fun nextAfterMidnight() {
        val p = plan().copy(hour = 0, minute = 10)
        assertEquals("2026-11-07T21:10:00Z", Schedule.next(p, Instant.parse("2026-11-07T20:59:00Z"), zone).toString())
    }
    @Test fun weekdaysSkipWeekend() {
        assertEquals("2026-11-09T04:00:00Z", Schedule.next(plan().copy(days = setOf(1,2,3,4,5)), Instant.parse("2026-11-06T05:00:00Z"), zone).toString())
    }
    @Test fun oneShotAfterExactMinuteGoesTomorrow() {
        assertEquals("2026-11-08T04:00:00Z", Schedule.next(plan(), Instant.parse("2026-11-07T04:00:00Z"), zone).toString())
    }
    @Test fun timezoneUsesLocalWallClock() {
        val now = Instant.parse("2026-11-07T00:00:00Z")
        assertNotEquals(Schedule.next(plan(), now, zone), Schedule.next(plan(), now, ZoneId.of("Asia/Tokyo")))
    }
    @Test fun daylightGapMovesForward() {
        val result = Schedule.next(plan().copy(hour = 2, minute = 30), Instant.parse("2026-03-29T00:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), result)
    }
    @Test fun daylightOverlapFiresOnlyOnce() {
        val result = Schedule.next(plan().copy(hour = 2, minute = 30), Instant.parse("2026-10-25T00:45:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), result)
    }
    private fun waiting(): AlarmRun {
        val ringing = WakeMachine.due(run(), 0, 1000)
        val started = WakeMachine.startMission(ringing, 1100)
        return WakeMachine.progress(started, started.version, 2000)
    }
    @Test fun completeWakeLifecycle() {
        val wait = waiting()
        assertEquals(RunState.WAITING_WAKE_CHECK, wait.state)
        assertEquals(182000L, wait.eventAt)
        val active = WakeMachine.due(wait, wait.version, wait.eventAt)
        assertEquals(282000L, active.deadline)
        val done = WakeMachine.confirm(active, 200000, false)
        assertEquals(RunState.COMPLETED, done.state)
        assertFalse(done.needsEvent)
        assertEquals(done, WakeMachine.due(done, active.version, 300000))
    }
    @Test fun timeoutReringsAndRestartsCycle() {
        val wait = waiting(); val active = WakeMachine.due(wait, wait.version, wait.eventAt)
        val again = WakeMachine.due(active, active.version, active.deadline)
        assertEquals(RunState.RE_RINGING, again.state); assertEquals(1, again.cycle)
        val mission = WakeMachine.startMission(again, 300000)
        assertEquals(RunState.WAITING_WAKE_CHECK, WakeMachine.progress(mission, mission.version, 310000).state)
    }
    @Test fun lateDeliveryCannotExtendDeadline() {
        val wait = waiting()
        assertEquals(RunState.RE_RINGING, WakeMachine.due(wait, wait.version, 400000).state)
    }
    @Test fun duplicateBroadcastIsIdempotent() {
        val fired = WakeMachine.due(run(), 0, 1000)
        assertEquals(fired, WakeMachine.due(fired, 0, 1000))
    }
    @Test fun earlyOrStaleDeliveryDoesNothing() {
        assertEquals(run(), WakeMachine.due(run(), 0, 999))
        assertEquals(run(), WakeMachine.due(run(), 9, 2000))
    }
    @Test fun snoozeLimitAndResume() {
        var r = WakeMachine.due(run().copy(plan = plan().copy(snoozeLimit = 1)), 0, 1000)
        r = WakeMachine.snooze(r, 1100)
        assertEquals(301100L, r.eventAt)
        r = WakeMachine.due(r, r.version, r.eventAt)
        assertEquals(r, WakeMachine.snooze(r, 400000))
    }
    @Test fun confirmationAtDeadlineIsTooLate() {
        val wait = waiting(); val active = WakeMachine.due(wait, wait.version, wait.eventAt)
        assertEquals(active, WakeMachine.confirm(active, active.deadline, false))
    }
    @Test fun strictAndMultipleChecks() {
        val wait = waiting().copy(plan = plan().copy(strictCheck = true, checkCount = 2))
        val active = WakeMachine.due(wait, wait.version, wait.eventAt)
        assertEquals(active, WakeMachine.confirm(active, 190000, false))
        val second = WakeMachine.confirm(active, 190000, true)
        assertEquals(RunState.WAITING_WAKE_CHECK, second.state); assertEquals(790000L, second.eventAt)
        val active2 = WakeMachine.due(second, second.version, second.eventAt)
        assertEquals(RunState.COMPLETED, WakeMachine.confirm(active2, 800000, true).state)
    }
    @Test fun combinedMissionsCannotSkipStage() {
        val initial = run().copy(plan = plan().copy(missions = listOf(MissionSpec(count=1), MissionSpec(MissionKind.QR, 1))))
        val started = WakeMachine.startMission(WakeMachine.due(initial, 0, 1000), 1100)
        val second = WakeMachine.progress(started, started.version, 1200)
        assertEquals(1, second.missionIndex); assertEquals(RunState.MISSION_RUNNING, second.state)
        assertEquals(second, WakeMachine.progress(second, started.version, 1201))
    }
    @Test fun parallelAlarmsAreIndependent() {
        val a = waiting(); val b = run().copy(id = "second")
        val cancelled = WakeMachine.cancel(a, 200000, true)
        assertTrue(cancelled.emergency); assertEquals(RunState.SCHEDULED, b.state)
        assertEquals(RunState.RINGING, WakeMachine.due(b, 0, 2000).state)
    }
    @Test fun persistenceRestoresExactProgressAndDeadline() {
        val wait = waiting(); val active = WakeMachine.due(wait, wait.version, wait.eventAt)
        assertEquals(active, Json.decodeFromString<AlarmRun>(Json.encodeToString(active)))
    }
    @Test fun allMathExpressionsHaveCorrectResults() {
        for (difficulty in 1..3) for (seed in 0..999) {
            val m = Missions.math(seed, difficulty)
            val n = Regex("\\d+").findAll(m.text).map { it.value.toInt() }.toList()
            val answer = when {
                "(" in m.text -> (n[0] + n[1]) * n[2]
                "+" in m.text -> n[0] + n[1]
                "−" in m.text -> n[0] - n[1]
                "×" in m.text -> n[0] * n[1]
                else -> { assertNotEquals(0, n[1]); assertEquals(0, n[0] % n[1]); n[0] / n[1] }
            }
            assertEquals(answer, m.answer)
        }
    }
    @Test fun qrRequiresRegisteredExactValue() {
        assertFalse(Missions.qrMatches("", "")); assertFalse(Missions.qrMatches("sink", "other"))
        assertFalse(Missions.qrMatches("sink", "sink ")); assertTrue(Missions.qrMatches("sink", "sink"))
    }
    @Test fun unicodeTextAndMemoryAreDeterministic() {
        assertTrue(Missions.textMatches("й", "и\u0306"))
        assertEquals(Missions.memory(2,3,5), Missions.memory(2,3,5))
        assertTrue(Missions.memory(5,3,20).all { it in 0..8 })
    }
    @Test fun missingSensorProvidesFallback() {
        assertFalse(Missions.available(MissionKind.STEPS, false))
        val started = WakeMachine.startMission(WakeMachine.due(run(), 0, 1000), 1100)
        assertTrue(WakeMachine.fallback(started, 1200).fallbackMath)
    }
    @Test fun stepBaselineExcludesPastSteps() {
        val counter = StepCounter(); assertEquals(0, counter.sample(800)); assertEquals(2, counter.sample(802))
        assertEquals(0, counter.sample(0)); assertEquals(1, counter.sample(1))
    }
    @Test fun shakeRejectsNoiseAndDoubleCounting() {
        val detector = ShakeDetector()
        assertFalse(detector.sample(0f, 0f, 9.8f, 1000)); assertTrue(detector.sample(0f, 0f, 19f, 1100))
        assertFalse(detector.sample(0f, 0f, 21f, 1900))
        detector.sample(0f, 0f, 9f, 2000); assertTrue(detector.sample(0f, 0f, 19f, 2100))
    }
    @Test fun cancelledEditedOccurrenceCannotFire() {
        val cancelled = WakeMachine.cancel(run(), 500)
        assertEquals(cancelled, WakeMachine.due(cancelled, 0, 1000))
        assertEquals(cancelled, WakeMachine.due(cancelled, cancelled.version, 1000))
    }
}

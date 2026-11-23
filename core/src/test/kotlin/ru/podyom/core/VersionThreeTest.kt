package ru.podyom.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VersionThreeTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val monday = LocalDate.of(2026, 11, 23)
    private val daily = AlarmPlan("a", hour = 6, days = (1..7).toSet())
    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = this.zone) =
        date.atTime(hour, minute).atZone(zone).toInstant()

    @Test fun tomorrowSkipKeepsTheDayAfterTomorrow() {
        val plan = daily.copy(skipEpochDays = setOf(monday.plusDays(1).toEpochDay()))
        assertEquals(at(monday.plusDays(2), 6), Schedule.next(plan, at(monday, 7), zone))
    }

    @Test fun tomorrowSkipDoesNotCancelTodaysAlarm() {
        val plan = daily.copy(days = setOf(1, 2), skipEpochDays = setOf(monday.plusDays(1).toEpochDay()))
        assertEquals(at(monday, 6), Schedule.next(plan, at(monday, 5), zone))
        assertEquals(at(monday.plusWeeks(1), 6), Schedule.next(plan, at(monday, 6), zone))
    }

    @Test fun saturdayIsNotAnOccurrenceForWeekdays() {
        val plan = daily.copy(days = (1..5).toSet())
        assertFalse(Schedule.hasOccurrenceOn(plan, monday.plusDays(5), zone, null))
        assertEquals(at(monday.plusWeeks(1), 6), Schedule.next(plan, at(monday.plusDays(4), 7), zone))
    }

    @Test fun calendarDateStaysExcludedAfterZoneChangeAndMidnight() {
        val nextDay = monday.plusDays(1)
        val plan = daily.copy(skipEpochDays = setOf(nextDay.toEpochDay()))
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(at(nextDay.plusDays(1), 6, zone = tokyo), Schedule.next(plan, at(nextDay, 0, zone = tokyo), tokyo))
    }

    @Test fun changingTimeKeepsTheSkipAndDoesNotRepeatAConsumedLocalDay() {
        val plan = daily.copy(hour = 7, skipEpochDays = setOf(monday.plusDays(1).toEpochDay()), lastFiredEpochDay = monday.toEpochDay())
        assertEquals(at(monday.plusDays(2), 7), Schedule.next(plan, at(monday, 5), zone))
    }

    @Test fun oneShotHasNoReplacementUntilSkipIsUndone() {
        val occurrence = at(monday.plusDays(1), 6)
        val plan = daily.copy(days = emptySet(), oneShotAt = occurrence.toEpochMilli(), skipEpochDays = setOf(monday.plusDays(1).toEpochDay()))
        assertNull(Schedule.nextOrNull(plan, at(monday, 5), zone))
        assertEquals(occurrence, Schedule.next(plan.copy(skipEpochDays = emptySet()), at(monday, 5), zone))
    }

    @Test fun eachOfFourAlarmsTakesOverEvenWhenTheEarlierPlayerHasFailed() {
        var broken = AudioRecovery()
        repeat(4) { broken = broken.failed(canFallback = true) }
        assertTrue(broken.exhausted)
        val active = mutableListOf<AlarmRun>()
        repeat(4) { index ->
            val instant = at(monday, 6, index * 10).toEpochMilli()
            val pending = AlarmRun("run-$index", daily.copy(id = "alarm-$index"), instant)
            val ringing = WakeMachine.due(pending, pending.version, instant)
            active += ringing
            assertEquals(ringing.id, RingingPriority.next(active)?.id)
            assertEquals(ringing.id, RingingPriority.next(active.reversed())?.id)
            assertEquals(ringing, WakeMachine.due(ringing, pending.version, instant))
            assertFalse(AudioRecovery().exhausted)
        }
        assertTrue(active.all { it.state == RunState.RINGING })
        val last = active.removeAt(active.lastIndex)
        assertEquals("run-2", RingingPriority.next(active + WakeMachine.cancel(last, last.ringingSince!!))?.id)
    }

    @Test fun aLaterWakeTimeoutAlsoTakesPriority() {
        val start = 1_000L
        val first = AlarmRun("first", daily, start, state = RunState.WAKE_CHECK_ACTIVE,
            eventAt = start + 30_000, deadline = start + 30_000)
        val second = WakeMachine.due(AlarmRun("second", daily, start+20_000), 0, start+20_000)
        val repeat = WakeMachine.due(first, first.version, first.deadline)
        assertEquals("first", RingingPriority.next(listOf(repeat, second))?.id)
    }

    @Test fun audioRecoveryUsesFallbackAndExhaustsAfterThreeRetries() {
        val first = AudioRecovery().failed(canFallback = true)
        assertTrue(first.useFallback)
        assertEquals(250L, first.retryDelayMillis)
        val second = first.failed(canFallback = false)
        assertTrue(second.useFallback)
        assertEquals(1_000L, second.retryDelayMillis)
        val third = second.failed(canFallback = false)
        assertEquals(3_000L, third.retryDelayMillis)
        assertFalse(third.exhausted)
        val exhausted = third.failed(canFallback = false)
        assertTrue(exhausted.exhausted)
        assertEquals(exhausted, exhausted.failed(canFallback = false))
    }
}

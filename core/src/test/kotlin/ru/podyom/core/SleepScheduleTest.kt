package ru.podyom.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SleepScheduleTest {
    @Test fun weekdayBelongsToBedtimeEvenIfNotificationIsBeforeMidnight() {
        val config = SleepSettings(enabled = true, hour = 0, minute = 10, days = setOf(5), leadMinutes = 30)
        val zone = ZoneId.of("Europe/Moscow")
        val thursday = Instant.parse("2026-11-26T19:00:00Z")
        val occurrence = requireNotNull(SleepSchedule.next(config, thursday, zone))
        assertEquals(Instant.parse("2026-11-26T20:40:00Z"), occurrence.notifyAt)
        assertEquals(Instant.parse("2026-11-26T21:10:00Z"), occurrence.bedAt)
        assertEquals(Instant.parse("2026-12-03T20:40:00Z"), SleepSchedule.next(config, occurrence.notifyAt, zone)?.notifyAt)
    }

    @Test fun timezoneAndDstUseLocalTimeWhileDisabledHasNoEvent() {
        val config = SleepSettings(enabled = true, hour = 2, minute = 15, leadMinutes = 60, days = setOf(7))
        val after = Instant.parse("2026-03-29T00:00:00Z")
        val gap = requireNotNull(SleepSchedule.next(config, after, ZoneId.of("Europe/Berlin")))
        assertEquals(Instant.parse("2026-03-29T01:15:00Z"), gap.bedAt)
        assertEquals(Instant.parse("2026-03-29T00:15:00Z"), gap.notifyAt)
        assertEquals(Instant.parse("2026-03-29T01:15:00Z"), SleepSchedule.next(config, after, ZoneId.of("UTC"))?.notifyAt)
        assertNull(SleepSchedule.next(config.copy(enabled = false), after, ZoneId.of("UTC")))
    }
}

package ru.podyom.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class SleepSettings(
    val enabled: Boolean = false,
    val hour: Int = 22,
    val minute: Int = 30,
    val days: Set<Int> = (1..7).toSet(),
    val leadMinutes: Int = 30,
) {
    fun validate() {
        require(hour in 0..23 && minute in 0..59) { "Проверьте время сна" }
        require(days.isNotEmpty() && days.all { it in 1..7 }) { "Выберите хотя бы один день" }
        require(leadMinutes in setOf(15, 30, 60)) { "Выберите интервал напоминания" }
    }
}

data class SleepOccurrence(val notifyAt: Instant, val bedAt: Instant)

object SleepSchedule {
    fun next(settings: SleepSettings, after: Instant, zone: ZoneId): SleepOccurrence? {
        if (!settings.enabled) return null
        settings.validate()
        val date = after.atZone(zone).toLocalDate()
        for (offset in 0..8L) {
            val bedtimeDate = date.plusDays(offset)
            if (bedtimeDate.dayOfWeek.value !in settings.days) continue
            val bed = bedtimeDate.atTime(settings.hour, settings.minute).atZone(zone)
                .withEarlierOffsetAtOverlap().toInstant()
            val notification = bed.minusSeconds(60L * settings.leadMinutes)
            if (notification > after) return SleepOccurrence(notification, bed)
        }
        return null
    }
}

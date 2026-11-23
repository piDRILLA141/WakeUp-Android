package ru.podyom.core

import java.time.*

object Schedule {
    /** Local wall clock schedule. Gaps move forward; overlaps fire once at the earlier offset. */
    fun next(plan: AlarmPlan, after: Instant, zone: ZoneId): Instant =
        requireNotNull(nextOrNull(plan, after, zone)) { "У одноразового будильника нет следующего события" }

    fun nextOrNull(plan: AlarmPlan, after: Instant, zone: ZoneId): Instant? {
        plan.validate()
        if (plan.days.isEmpty() && plan.oneShotAt != null) {
            return if (plan.skipEpochDays.isEmpty()) Instant.ofEpochMilli(plan.oneShotAt) else null
        }
        val localToday = after.atZone(zone).toLocalDate()
        for (offset in 0L..21L) {
            val date = localToday.plusDays(offset)
            if (date.toEpochDay() in plan.skipEpochDays || date.toEpochDay() == plan.lastFiredEpochDay) continue
            if (plan.days.isNotEmpty() && date.dayOfWeek.value !in plan.days) continue
            val candidate = wallTime(plan, date, zone)
            // Whole-day gaps (for example a historical dateline shift) can map to the next date.
            if (candidate.atZone(zone).toLocalDate().toEpochDay() in plan.skipEpochDays) continue
            if (candidate > after) return candidate
        }
        error("Не найдено следующее срабатывание в течение трёх недель")
    }

    fun hasOccurrenceOn(plan: AlarmPlan, date: LocalDate, zone: ZoneId, pendingAt: Long?): Boolean {
        if (plan.days.isEmpty()) {
            val at = pendingAt ?: plan.oneShotAt ?: return false
            return Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == date
        }
        return date.dayOfWeek.value in plan.days && wallTime(plan, date, zone).atZone(zone).toLocalDate() == date
    }

    private fun wallTime(plan: AlarmPlan, date: LocalDate, zone: ZoneId) =
        date.atTime(plan.hour, plan.minute).atZone(zone).withEarlierOffsetAtOverlap().toInstant()
}

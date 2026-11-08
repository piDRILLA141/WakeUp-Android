package ru.podyom.core

import java.time.*

object Schedule {
    /** Local wall clock schedule. Gaps move forward; overlaps fire once at the earlier offset. */
    fun next(plan: AlarmPlan, after: Instant, zone: ZoneId): Instant {
        plan.validate()
        if(plan.days.isEmpty() && plan.oneShotAt != null) return Instant.ofEpochMilli(plan.oneShotAt)
        val today = after.atZone(zone).toLocalDate()
        for (offset in 0L..14L) {
            val date = today.plusDays(offset)
            if (plan.days.isNotEmpty() && date.dayOfWeek.value !in plan.days) continue
            val candidate = date.atTime(plan.hour, plan.minute).atZone(zone).withEarlierOffsetAtOverlap().toInstant()
            if (candidate > after) return candidate
        }
        error("No occurrence in two weeks")
    }
}

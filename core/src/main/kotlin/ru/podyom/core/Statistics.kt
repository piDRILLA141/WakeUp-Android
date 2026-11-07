package ru.podyom.core

import java.time.*

object Statistics {
    fun streak(runs: List<AlarmRun>, today: LocalDate, zone: ZoneId): Int {
        val dates = runs.filter { it.state == RunState.COMPLETED && it.plan.wakeCheck }.map {
            Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate()
        }.toSet()
        var cursor = if(today in dates) today else today.minusDays(1)
        var days = 0
        while(cursor in dates) { days++; cursor=cursor.minusDays(1) }
        return days
    }
}

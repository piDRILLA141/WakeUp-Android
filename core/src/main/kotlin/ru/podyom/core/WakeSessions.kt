package ru.podyom.core

import java.time.Instant
import java.time.ZoneId

object WakeSessions {
    const val WINDOW_MS = 90 * 60 * 1000L
    private val order = compareBy<AlarmRun> { it.scheduledAt }.thenBy { it.id }

    fun inWindow(first: Long, second: Long, zone: ZoneId): Boolean =
        second >= first && second - first <= WINDOW_MS &&
            Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(second).atZone(zone).toLocalDate()

    fun replaces(older: AlarmRun, newer: AlarmRun, zone: ZoneId, ungrouped: Boolean): Boolean {
        if (order.compare(older, newer) >= 0 || older.id == newer.id ||
            older.plan.quick || newer.plan.quick || older.plan.independent || newer.plan.independent ||
            older.plan.groupId != newer.plan.groupId || older.snoozes > 0 || older.state == RunState.SNOOZED ||
            !inWindow(older.scheduledAt, newer.scheduledAt, zone)) return false
        return if (older.plan.groupId != null) !older.terminal
        else ungrouped && older.cycle == 0 && older.state in setOf(RunState.SCHEDULED, RunState.RINGING, RunState.MISSION_RUNNING)
    }
}

package ru.podyom.core

import kotlinx.serialization.Serializable

@Serializable enum class MissionKind(val title: String) {
    MATH("Математика"), MEMORY("Память"), TEXT("Набор текста"), SHAKE("Встряхивание"), STEPS("Шаги"), QR("QR / штрихкод")
}
@Serializable data class MissionSpec(
    val kind: MissionKind = MissionKind.MATH,
    val count: Int = 3,
    val difficulty: Int = 1,
    val grid: Int = 3,
    val length: Int = 4,
) {
    fun validate() {
        require(count in 1..50 && difficulty in 1..3 && grid in 2..4 && length in 2..12)
        if (kind == MissionKind.MATH) require(count <= 20)
    }
}
/** Contains no labels, user text, codes or media URIs. Safe minimal Direct Boot payload. */
@Serializable data class AlarmPlan(
    val id: String,
    val privateKey: String = id,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val missions: List<MissionSpec> = listOf(MissionSpec()),
    val wakeCheck: Boolean = true,
    val wakeDelaySeconds: Int = 180,
    val confirmSeconds: Int = 100,
    val checkCount: Int = 1,
    val laterCheckSeconds: Int = 600,
    val strictCheck: Boolean = false,
    val snoozeSeconds: Int = 300,
    val snoozeLimit: Int = 2,
    val vibrate: Boolean = true,
    val volume: Float = 0.8f,
    val rampSeconds: Int = 20,
    val duckDuringMission: Boolean = true,
) {
    fun validate() {
        require(id.isNotBlank() && privateKey.isNotBlank())
        require(hour in 0..23 && minute in 0..59 && days.all { it in 1..7 })
        require(missions.size in 1..5); missions.forEach { it.validate() }
        require(wakeDelaySeconds in 5..86400 && confirmSeconds in 5..600)
        require(checkCount in 1..3 && laterCheckSeconds in 5..86400)
        require(snoozeSeconds in 10..3600 && snoozeLimit in 0..10)
        require(volume in 0.05f..1f && rampSeconds in 0..120)
    }
}
@Serializable data class PrivateAlarm(
    val title: String = "Новое утро",
    val soundUri: String = "",
    val phrase: String = "Я проснулся и начинаю новый день",
    val qr: String = "",
)
@Serializable enum class RunState {
    SCHEDULED, RINGING, MISSION_RUNNING, WAITING_WAKE_CHECK, WAKE_CHECK_ACTIVE,
    RE_RINGING, SNOOZED, COMPLETED, CANCELLED
}
@Serializable data class AlarmRun(
    val id: String,
    val plan: AlarmPlan,
    val scheduledAt: Long,
    val state: RunState = RunState.SCHEDULED,
    val eventAt: Long = scheduledAt,
    val deadline: Long = 0,
    val missionFinishedAt: Long = 0,
    val firedAt: Long = 0,
    val cycle: Int = 0,
    val snoozes: Int = 0,
    val checkIndex: Int = 0,
    val missionIndex: Int = 0,
    val progress: Int = 0,
    val seed: Int = id.hashCode(),
    val version: Int = 0,
    val updatedAt: Long = scheduledAt,
    val emergency: Boolean = false,
    val missedChecks: Int = 0,
    val fallbackMath: Boolean = false,
) {
    val terminal get() = state == RunState.COMPLETED || state == RunState.CANCELLED
    val sounding get() = state in setOf(RunState.RINGING, RunState.MISSION_RUNNING, RunState.RE_RINGING)
    val needsEvent get() = state in setOf(RunState.SCHEDULED, RunState.SNOOZED, RunState.WAITING_WAKE_CHECK, RunState.WAKE_CHECK_ACTIVE)
    val mission get() = if (fallbackMath) MissionSpec(count = 3) else plan.missions[missionIndex.coerceAtMost(plan.missions.lastIndex)]
}

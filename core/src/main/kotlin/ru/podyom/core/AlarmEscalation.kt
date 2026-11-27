package ru.podyom.core

object AlarmEscalation {
    val intervals = listOf(30, 60, 120, 300)

    fun due(run: AlarmRun, ringingAgeMillis: Long): Boolean {
        val interval = run.plan.escalationSeconds ?: return false
        return run.state in setOf(RunState.RINGING, RunState.RE_RINGING) &&
            ringingAgeMillis >= interval * 1_000L
    }

    fun playerVolume(run: AlarmRun, playbackMillis: Long): Float {
        val ramp = run.plan.rampSeconds * 1_000L
        val proportion = if (ramp == 0L) 1f else (playbackMillis.toFloat() / ramp).coerceIn(.05f, 1f)
        val duck = if (run.state == RunState.MISSION_RUNNING && run.plan.duckDuringMission) .25f else 1f
        return run.plan.volume * proportion * duck
    }
}

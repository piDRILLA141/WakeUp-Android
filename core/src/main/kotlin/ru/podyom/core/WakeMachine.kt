package ru.podyom.core

/** Pure reducer. All accepted transitions must be durably committed before UI sees them. */
object WakeMachine {
    private fun AlarmRun.changed(now: Long) = copy(version = version + 1, updatedAt = now)
    fun due(run: AlarmRun, token: Int, now: Long): AlarmRun {
        if (token != run.version || !run.needsEvent || now < run.eventAt) return run
        return when (run.state) {
            RunState.SCHEDULED, RunState.SNOOZED -> run.copy(state = RunState.RINGING, firedAt = if (run.firedAt == 0L) now else run.firedAt)
            RunState.WAITING_WAKE_CHECK -> {
                // Deadline is tied to the scheduled check, never extended by a late delivery.
                val deadline = run.eventAt + run.plan.confirmSeconds * 1000L
                if (now >= deadline) rerun(run) else run.copy(state = RunState.WAKE_CHECK_ACTIVE, deadline = deadline, eventAt = deadline)
            }
            RunState.WAKE_CHECK_ACTIVE -> rerun(run)
            else -> run
        }.changed(now)
    }
    private fun rerun(r: AlarmRun) = r.copy(state = RunState.RE_RINGING, cycle = r.cycle + 1,
        missedChecks = r.missedChecks + 1, missionIndex = 0, progress = 0, fallbackMath = false, deadline = 0)
    fun startMission(r: AlarmRun, now: Long): AlarmRun =
        if (!r.plan.quick && r.state in setOf(RunState.RINGING, RunState.RE_RINGING)) r.copy(state = RunState.MISSION_RUNNING).changed(now) else r
    fun fallback(r: AlarmRun, now: Long): AlarmRun =
        if (r.state == RunState.MISSION_RUNNING) r.copy(fallbackMath = true, progress = 0).changed(now) else r
    fun progress(r: AlarmRun, token: Int, now: Long): AlarmRun {
        if (token != r.version || r.state != RunState.MISSION_RUNNING) return r
        if (r.progress + 1 < r.mission.count) return r.copy(progress = r.progress + 1).changed(now)
        if (r.missionIndex + 1 < r.plan.missions.size) return r.copy(missionIndex = r.missionIndex + 1, progress = 0, fallbackMath = false).changed(now)
        return r.copy(missionFinishedAt = now, progress = 0, missionIndex = 0, fallbackMath = false,
            state = if (r.plan.wakeCheck) RunState.WAITING_WAKE_CHECK else RunState.COMPLETED,
            eventAt = now + r.plan.wakeDelaySeconds * 1000L).changed(now)
    }
    fun confirm(r: AlarmRun, now: Long, strictPassed: Boolean): AlarmRun {
        if (r.state != RunState.WAKE_CHECK_ACTIVE || now >= r.deadline || (r.plan.strictCheck && !strictPassed)) return r
        return if (r.checkIndex + 1 < r.plan.checkCount) r.copy(state = RunState.WAITING_WAKE_CHECK,
            checkIndex = r.checkIndex + 1, deadline = 0, eventAt = now + r.plan.laterCheckSeconds * 1000L).changed(now)
        else r.copy(state = RunState.COMPLETED, deadline = 0).changed(now)
    }
    fun dismissQuick(r: AlarmRun, now: Long): AlarmRun =
        if(r.plan.quick && r.sounding) r.copy(state=RunState.COMPLETED).changed(now) else r
    fun snooze(r: AlarmRun, now: Long): AlarmRun {
        if (!r.sounding || r.snoozes >= r.plan.snoozeLimit) return r
        return r.copy(state = RunState.SNOOZED, snoozes = r.snoozes + 1, missionIndex = 0, progress = 0,
            fallbackMath = false, eventAt = now + r.plan.snoozeSeconds * 1000L).changed(now)
    }
    fun cancel(r: AlarmRun, now: Long, emergency: Boolean = false): AlarmRun =
        if (r.terminal) r else r.copy(state = RunState.CANCELLED, emergency = emergency, deadline = 0).changed(now)
}

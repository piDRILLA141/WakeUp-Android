package ru.podyom.core

/** Stable across clock rebasing. Uniquely identifies a check within an occurrence. */
data class CheckKey(val runId: String, val cycle: Int, val index: Int) {
    fun matches(run: AlarmRun) = run.id == runId && run.cycle == cycle && run.checkIndex == index
}

val AlarmRun.checkKey: CheckKey get() = CheckKey(id, cycle, checkIndex)

fun nextActiveCheck(runs: List<AlarmRun>, now: Long, excludeId: String? = null): AlarmRun? =
    runs.asSequence().filter { it.id != excludeId && it.state == RunState.WAKE_CHECK_ACTIVE && it.deadline > now }
        .minWithOrNull(compareBy<AlarmRun> { it.deadline }.thenBy { it.id })

enum class CheckPage { LOADING, MISSING, ACTIVE, CONFIRMED, EXPIRED, RE_RING, CANCELLED, CHANGED }

fun checkPage(key: CheckKey, runs: List<AlarmRun>?, now: Long): CheckPage {
    if (runs == null) return CheckPage.LOADING
    val run = runs.firstOrNull { it.id == key.runId } ?: return CheckPage.MISSING
    if (run.state == RunState.CANCELLED) return CheckPage.CANCELLED
    if (run.state == RunState.MISSED) return CheckPage.EXPIRED
    if (run.cycle > key.cycle) return if (run.sounding) CheckPage.RE_RING else CheckPage.EXPIRED
    if (run.cycle != key.cycle) return CheckPage.CHANGED
    if (run.checkIndex > key.index || (run.checkIndex == key.index && run.state == RunState.COMPLETED))
        return CheckPage.CONFIRMED
    if (!key.matches(run) || run.state != RunState.WAKE_CHECK_ACTIVE) return CheckPage.CHANGED
    return if (now < run.deadline) CheckPage.ACTIVE else CheckPage.EXPIRED
}

enum class CheckConfirmation { ACCEPTED, ALREADY_CONFIRMED, EXPIRED, CHANGED, WRONG_ANSWER, MISSING }

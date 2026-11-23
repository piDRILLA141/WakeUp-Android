package ru.podyom.core

/** Reconstructs playback order using persisted occurrence times, independent of UI and process. */
object RingingPriority {
    fun ordered(runs: Iterable<AlarmRun>): List<AlarmRun> = runs.filter { it.sounding }.sortedWith(
        compareByDescending<AlarmRun> { it.ringingSince ?: it.firedAt.takeIf { at -> at > 0 } ?: it.scheduledAt }
            .thenByDescending { it.scheduledAt }
            .thenBy { it.id }
    )

    fun next(runs: Iterable<AlarmRun>): AlarmRun? = ordered(runs).firstOrNull()
}

/** Recovery budget belongs to one selected ring, never to the service or the next alarm. */
data class AudioRecovery(val failures: Int = 0, val useFallback: Boolean = false) {
    val exhausted: Boolean get() = failures > RETRY_DELAYS_MILLIS.size
    val retryDelayMillis: Long get() = RETRY_DELAYS_MILLIS.getOrElse(failures - 1) { 0L }

    fun failed(canFallback: Boolean): AudioRecovery = copy(
        failures = (failures + 1).coerceAtMost(RETRY_DELAYS_MILLIS.size + 1),
        useFallback = useFallback || canFallback,
    )

    private companion object {
        val RETRY_DELAYS_MILLIS = listOf(250L, 1_000L, 3_000L)
    }
}

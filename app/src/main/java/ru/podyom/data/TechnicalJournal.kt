package ru.podyom.data

import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import ru.podyom.App
import ru.podyom.core.AlarmRun
import ru.podyom.ringing.AudioSignal

enum class TechnicalEvent {
    ALARM_SCHEDULED, ALARM_DELIVERED, RING_STARTED, AUDIO_STARTED, AUDIO_ERROR, AUDIO_RECOVERY,
    AUDIO_FOCUS_LOST, AUDIO_FOCUS_REGAINED, RING_PRIORITY_CHANGED, MISSION_COMPLETED,
    WAKE_CHECK_SCHEDULED, WAKE_CHECK_STARTED, NOTIFICATION_OPENED, WAKE_ACTIVITY_CREATED,
    WAKE_ACTIVITY_RESUMED, OVERLAY_SHOWN, OVERLAY_DISMISSED, WAKE_CHECK_CONFIRMED,
    WAKE_CHECK_EXPIRED, RE_RING_STARTED, ALARM_MISSED, SCHEDULE_RESTORED, PERMISSION_MISSING,
    SCHEDULE_ERROR, LAUNCH_ERROR, AUDIO_ROUTE_CHANGED,
}

@Serializable
data class TechnicalRecord(val timestampMs: Long, val event: TechnicalEvent, val runId: String?, val version: Int?, val code: String?)

class TechnicalJournal(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao get() = app.runtime.dao()

    fun report(event: TechnicalEvent, id: String? = null, version: Int? = null, code: String? = null) {
        scope.launch {
            try { record(event, id, version, code) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Diagnostics must never interrupt the alarm. */ }
        }
    }

    suspend fun record(event: TechnicalEvent, id: String? = null, version: Int? = null, code: String? = null) {
        app.runtime.withTransaction { insert(event, id, version, code) }
    }

    // The engine commits this event together with the corresponding state transition.
    internal suspend fun recordInTransaction(event: TechnicalEvent, run: AlarmRun) =
        insert(event, run.id, run.version, null)

    private suspend fun insert(event: TechnicalEvent, id: String?, version: Int?, code: String?) {
        val safeId = id?.takeIf { ID.matches(it) }.orEmpty()
        val safeCode = code?.takeIf { it in FIXED_CODES || AudioSignal.entries.any { signal -> signal.name == it } }.orEmpty()
        dao.event(EventRow(runId = safeId, at = System.currentTimeMillis(),
            type = "TECH|${event.name}|${version ?: -1}|$safeCode"))
        dao.trimTechnicalEvents()
    }

    suspend fun recent() = dao.technicalEvents().mapNotNull(::decode)

    companion object {
        private val ID = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val FIXED_CODES = setOf("notification", "full_screen", "overlay", "list", "unknown",
            "exact_alarm", "foreground_service", "registration", "overlay_window", "ring_limit")

        fun decode(row: EventRow): TechnicalRecord? {
            val fields = row.type.split('|')
            if (fields.size != 4 || fields[0] != "TECH") return null
            val event = TechnicalEvent.entries.firstOrNull { it.name == fields[1] } ?: return null
            return TechnicalRecord(row.at, event, row.runId.takeIf { ID.matches(it) },
                fields[2].toIntOrNull()?.takeIf { it >= 0 }, fields[3].takeIf { it.isNotEmpty() })
        }
    }
}

package ru.podyom.alarm

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.App
import ru.podyom.core.*
import ru.podyom.data.*
import java.time.*
import java.util.UUID

/** Single-process serialized coordinator; Room, not an Activity, owns every occurrence. */
class AlarmEngine(private val app: App, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val db get() = app.runtime
    private val dao get() = db.dao()
    private fun now() = clock()
    private suspend fun runs() = dao.runs().map { it.model() }
    private suspend fun put(previous: AlarmRun?, next: AlarmRun, reason: String = next.state.name) {
        dao.putRun(next.row())
        if (previous?.state != next.state || reason != next.state.name) dao.event(EventRow(runId = next.id, at = now(), type = reason))
    }
    private suspend fun create(plan: AlarmPlan, at: Long) {
        put(null, AlarmRun(UUID.randomUUID().toString(), plan, at))
    }
    private suspend fun reconcile(before: List<AlarmRun>, recoverSound: Boolean = false) {
        val after = runs()
        before.filter { old -> after.none { it.id == old.id && it.version == old.version } }.forEach(app.scheduler::cancel)
        after.filter { !it.terminal }.forEach { run ->
            try {
                if (run.needsEvent) app.scheduler.schedule(run)
                else if (recoverSound && run.sounding) app.scheduler.schedule(run, now() + 1000)
                dao.diagnostic(run.id, "")
            } catch (e: Exception) { dao.diagnostic(run.id, "Не удалось запланировать: ${e.javaClass.simpleName}") }
        }
        AlarmNotifications.refreshChecks(app, after)
    }
    suspend fun snapshot(): List<AlarmRun> = mutex.withLock { runs() }
    suspend fun profile(key: String): PrivateAlarm? = if (app.unlocked)
        app.privateDb.dao().profile(key)?.let { codec.decodeFromString<PrivateAlarm>(it.json) } else null
    suspend fun save(plan: AlarmPlan, profile: PrivateAlarm, testAt: Long? = null) = mutex.withLock {
        plan.validate()
        if (plan.enabled) check(app.scheduler.allowed()) { "Разрешите точные будильники в настройках Android" }
        require(profile.title.length <= 100 && profile.phrase.length in 1..500 && profile.qr.length <= 4096)
        require(plan.missions.none { it.kind == MissionKind.QR } || profile.qr.isNotEmpty()) { "Сначала зарегистрируйте QR-код" }
        // Immutable private version: editing tomorrow's alarm cannot alter today's active mission.
        val saved = plan.copy(privateKey = UUID.randomUUID().toString())
        app.privateDb.dao().putProfile(ProfileRow(saved.privateKey, codec.encodeToString(profile)))
        val before = runs()
        db.withTransaction {
            before.filter { it.plan.id == saved.id && it.state == RunState.SCHEDULED }.forEach { put(it, WakeMachine.cancel(it, now())) }
            dao.putAlarm(saved.row())
            if (saved.enabled) create(saved, testAt ?: Schedule.next(saved, Instant.ofEpochMilli(now()), ZoneId.systemDefault()).toEpochMilli())
        }
        reconcile(before)
    }
    suspend fun enable(id: String, enabled: Boolean) = mutex.withLock {
        if (enabled) check(app.scheduler.allowed()) { "Нет разрешения на точные будильники" }
        val plan = dao.alarms().firstOrNull { it.id == id }?.model()?.copy(enabled = enabled) ?: return@withLock
        val before = runs()
        db.withTransaction {
            dao.putAlarm(plan.row())
            before.filter { it.plan.id == id && it.state == RunState.SCHEDULED }.forEach { put(it, WakeMachine.cancel(it, now())) }
            if (enabled) create(plan, Schedule.next(plan, Instant.ofEpochMilli(now()), ZoneId.systemDefault()).toEpochMilli())
        }; reconcile(before)
    }
    suspend fun delete(id: String) = mutex.withLock {
        val before = runs()
        db.withTransaction {
            before.filter { it.plan.id == id && !it.terminal }.forEach { put(it, WakeMachine.cancel(it, now())) }
            dao.deleteAlarm(id)
        }; reconcile(before)
    }
    suspend fun skip(id: String) = mutex.withLock {
        val before = runs()
        val next = before.firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED } ?: return@withLock
        db.withTransaction {
            put(next, WakeMachine.cancel(next, now()), "SKIPPED")
            if (next.plan.days.isEmpty()) dao.putAlarm(next.plan.copy(enabled = false).row())
            else create(next.plan, Schedule.next(next.plan, Instant.ofEpochMilli(next.scheduledAt), ZoneId.systemDefault()).toEpochMilli())
        }; reconcile(before)
    }
    suspend fun event(id: String, token: Int) = mutex.withLock {
        val before = runs(); val r = before.firstOrNull { it.id == id } ?: return@withLock
        val next = WakeMachine.due(r, token, now())
        db.withTransaction {
            if (next != r) {
                put(r, next)
                if (r.state == RunState.SCHEDULED) {
                    val current = dao.alarms().firstOrNull { it.id == r.plan.id }?.model()
                    if (current?.enabled == true) {
                        if (current.days.isEmpty()) dao.putAlarm(current.copy(enabled = false).row())
                        else create(current, Schedule.next(current, Instant.ofEpochMilli(now()), ZoneId.systemDefault()).toEpochMilli())
                    }
                }
            }
        }; reconcile(before)
    }
    suspend fun update(id: String, operation: (AlarmRun, Long) -> AlarmRun) = mutex.withLock {
        val before = runs(); val run = before.firstOrNull { it.id == id } ?: return@withLock
        val changed = operation(run, now())
        db.withTransaction { if (changed != run) put(run, changed) }
        reconcile(before)
    }
    suspend fun restore(recalculate: Boolean = false) = mutex.withLock {
        val before = runs()
        db.withTransaction {
            if (recalculate) {
                before.filter { it.state == RunState.SCHEDULED }.forEach { put(it, WakeMachine.cancel(it, now()), "CLOCK_CHANGED") }
            }
            // Keep overdue occurrences. They are delivered immediately after reboot, not silently rolled to tomorrow.
            dao.alarms().map { it.model() }.filter { it.enabled }.forEach { plan ->
                if (runs().none { it.plan.id == plan.id && it.state == RunState.SCHEDULED })
                    create(plan, Schedule.next(plan, Instant.ofEpochMilli(now()), ZoneId.systemDefault()).toEpochMilli())
            }
        }
        reconcile(before, recoverSound = true)
    }
    suspend fun clearHistory() = mutex.withLock { db.withTransaction { dao.clearHistory(); dao.clearEvents() } }
}

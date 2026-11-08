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
class AlarmEngine(private val app: App, private val clock: () -> Long = System::currentTimeMillis, private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val mutex = Mutex()
    private var lastQuickAt = Long.MIN_VALUE
    private val db get() = app.runtime
    private val dao get() = db.dao()
    private fun now() = clock()
    private fun bootCount() = android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
    private suspend fun runs() = dao.runs().map { it.model() }
    private suspend fun put(previous: AlarmRun?, next: AlarmRun, reason: String = next.state.name) {
        val anchored = if (next.needsEvent && (next.state != RunState.SCHEDULED || next.plan.quick)) next.copy(
            eventElapsed = if (previous?.eventAt == next.eventAt && previous.bootCount == bootCount() && previous.eventElapsed > 0) previous.eventElapsed
                else elapsed() + (next.eventAt - now()), bootCount = bootCount()) else next.copy(eventElapsed = 0)
        dao.putRun(anchored.row())
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
            } catch (e: Exception) {
                val message = "Не удалось запланировать: ${e.javaClass.simpleName}"
                dao.diagnostic(run.id, message)
                AlarmNotifications.failure(app, message)
            }
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
        val failure = dao.runs().firstOrNull { it.alarmId == saved.id && it.state == RunState.SCHEDULED.name && it.diagnostic.isNotEmpty() }
        check(failure == null) { failure!!.diagnostic }
    }
    suspend fun duplicateLater(id: String): AlarmPlan {
        val original = mutex.withLock { dao.alarms().first { it.id == id }.model() }
        val at = mutex.withLock { runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }?.scheduledAt }
        val copy = AlarmShortcuts.copyForNext(original, UUID.randomUUID().toString(), Instant.ofEpochMilli(now()), ZoneId.systemDefault(),at)
        save(copy, profile(original.privateKey) ?: error("Личные настройки недоступны: разблокируйте телефон"))
        return copy
    }
    suspend fun quick(): AlarmPlan? {
        val instant = mutex.withLock {
            val time=now()
            if(lastQuickAt != Long.MIN_VALUE && time-lastQuickAt in 0..1500) return null
            lastQuickAt=time
            Instant.ofEpochMilli(time)
        }
        val plan=AlarmShortcuts.quick(UUID.randomUUID().toString(),instant,ZoneId.systemDefault())
        try { save(plan,PrivateAlarm(title="Через 20 минут"));return plan }
        catch(e:Exception) { mutex.withLock {lastQuickAt=Long.MIN_VALUE};throw e }
    }
    suspend fun confirmCheck(id:String, answer:String="", expectedVersion:Int?=null) = update(id) { r,t ->
        if(r.state!=RunState.WAKE_CHECK_ACTIVE) return@update r
        check(expectedVersion==null || r.version==expectedVersion) {"Эта проверка уже изменилась"}
        val passed = Missions.math(r.seed+r.checkIndex+r.cycle,1).answer == answer.toIntOrNull()
        val changed = WakeMachine.confirm(r,t,passed)
        check(changed!=r) {if(t>=r.deadline) "Время подтверждения истекло" else "Проверьте ответ"}
        changed
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
        val failure=dao.runs().firstOrNull {it.alarmId==id && it.state==RunState.SCHEDULED.name && it.diagnostic.isNotEmpty()}
        check(failure==null) {failure!!.diagnostic}
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
                // A manually changed wall clock must not turn a three-minute check into an hour.
                before.filter { it.needsEvent && (it.state != RunState.SCHEDULED || it.plan.quick) && it.bootCount == bootCount() && it.eventElapsed > 0 }.forEach { run ->
                    val rebased = now() + (run.eventElapsed - elapsed()).coerceAtLeast(0)
                    val delta = rebased - run.eventAt
                    val updated = run.copy(plan=if(run.plan.quick) run.plan.copy(oneShotAt=rebased) else run.plan, eventAt = rebased, deadline = if(run.deadline > 0) run.deadline + delta else 0,
                        version = run.version + 1, updatedAt = now())
                    put(run, updated, "CLOCK_REBASED")
                    if(run.plan.quick) dao.putAlarm(updated.plan.row())
                }
                before.filter { it.state == RunState.SCHEDULED && !it.plan.quick }.forEach { put(it, WakeMachine.cancel(it, now()), "CLOCK_CHANGED") }
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

package ru.podyom.alarm

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.podyom.ringing.AudioSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.App
import ru.podyom.core.*
import ru.podyom.data.*
import java.time.*
import java.util.UUID

/** Single-process serialized coordinator; Room, not an Activity, owns every occurrence. */
class AlarmEngine(private val app: App, private val clock: () -> Long = System::currentTimeMillis, private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime, private val zoneId: () -> ZoneId = ZoneId::systemDefault, private val registerReplacement: (AlarmRun) -> Unit = { app.scheduler.schedule(it) }) {
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
    private fun occurrence(plan: AlarmPlan, at: Long) = AlarmRun(UUID.randomUUID().toString(), plan, at)

    private suspend fun create(plan: AlarmPlan, at: Long) = put(null, occurrence(plan, at))

    suspend fun recordAudio(id: String, signal: AudioSignal) {
        dao.event(EventRow(runId = id, at = now(), type = "AUDIO_${signal.name}"))
    }

    suspend fun endCurrentRings(ids: Set<String>): Int = mutex.withLock {
        val before = runs()
        val active = before.filter { it.sounding && it.id in ids }
        if (active.isEmpty()) return@withLock 0
        db.withTransaction {
            active.forEach { put(it, WakeMachine.cancel(it, now(), emergency = true), "RING_CANCELLED_BY_USER") }
        }
        reconcile(before)
        active.size
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
        val oldPlan = dao.alarms().firstOrNull { it.id == plan.id }?.model()
        val zone = zoneId()
        val skippedOneShotAt = oldPlan?.takeIf {
            plan.days.isEmpty() && it.days.isEmpty() && it.skipEpochDays.isNotEmpty()
        }?.oneShotAt?.let { at ->
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atTime(plan.hour, plan.minute)
                .atZone(zone).toInstant().toEpochMilli()
        }
        val saved = plan.copy(
            oneShotAt = plan.oneShotAt ?: skippedOneShotAt,
            privateKey = UUID.randomUUID().toString(),
            skipEpochDays = if (oldPlan != null) oldPlan.skipEpochDays else plan.skipEpochDays,
            lastFiredEpochDay = oldPlan?.lastFiredEpochDay,
        )
        app.privateDb.dao().putProfile(ProfileRow(saved.privateKey, codec.encodeToString(profile)))
        val previous = runs().firstOrNull { it.plan.id == saved.id && it.state == RunState.SCHEDULED }
        val next = if (saved.enabled) {
            if (testAt != null) occurrence(saved, testAt) else newOccurrence(saved, now(), zoneId())
        } else null
        applyScheduleChanges(listOf(ScheduleChange(saved, previous, next)))
    }
    suspend fun duplicateLater(id: String): AlarmPlan {
        val original = mutex.withLock { dao.alarms().first { it.id == id }.model() }
        val at = mutex.withLock { runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }?.scheduledAt }
        val copy = AlarmShortcuts.copyForNext(original.copy(skipEpochDays = emptySet(), lastFiredEpochDay = null), UUID.randomUUID().toString(), Instant.ofEpochMilli(now()), zoneId(),at)
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
        val plan=AlarmShortcuts.quick(UUID.randomUUID().toString(),instant,zoneId())
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
    suspend fun enable(id: String, enabled: Boolean) {
        if (!enabled) {
            disableAll(setOf(id))
            return
        }
        mutex.withLock {
            check(app.scheduler.allowed()) { "Нет разрешения на точные будильники" }
            val plan = dao.alarms().firstOrNull { it.id == id }?.model() ?: return@withLock
            if (plan.enabled) return@withLock
            val updated = plan.copy(enabled = true, skipEpochDays = emptySet(),
                oneShotAt = plan.oneShotAt?.takeIf { it > now() })
            applyScheduleChanges(listOf(ScheduleChange(updated, null, newOccurrence(updated, now(), zoneId()))))
        }
    }

    data class BulkResult(val changed: Int, val notScheduled: Int, val alreadySkipped: Int = 0)

    suspend fun skipTomorrow(ids: Set<String>, allowOneShot: Boolean): BulkResult = mutex.withLock {
        val timestamp = now()
        val zone = zoneId()
        val tomorrow = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().plusDays(1)
        val plans = dao.alarms().map { it.model() }.filter { it.id in ids }
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val changed = mutableListOf<ScheduleChange>()
        var unavailable = ids.size - plans.size
        var already = 0
        plans.forEach { plan ->
            val existing = pending[plan.id]
            when {
                !plan.enabled -> unavailable++
                tomorrow.toEpochDay() in plan.skipEpochDays -> already++
                !Schedule.hasOccurrenceOn(plan, tomorrow, zone, existing?.scheduledAt) -> unavailable++
                plan.days.isEmpty() && !allowOneShot -> error("Подтвердите пропуск одноразовых будильников")
                else -> {
                    val updated = plan.copy(
                        skipEpochDays = plan.skipEpochDays.filter { it >= tomorrow.minusDays(1).toEpochDay() }.toSet() + tomorrow.toEpochDay(),
                        oneShotAt = if (plan.days.isEmpty()) existing?.scheduledAt ?: plan.oneShotAt else null,
                    )
                    val keep = existing?.takeIf { it.scheduledAt.asLocalDate(zone) != tomorrow }
                    val replacement = if (keep != null) null else newOccurrence(updated, timestamp, zone)
                    changed += ScheduleChange(updated, if (keep == null) existing else null, replacement)
                }
            }
        }
        applyScheduleChanges(changed)
        BulkResult(changed.size, unavailable, already)
    }

    suspend fun undoSkip(id: String, epochDay: Long? = null) = mutex.withLock {
        val plan = dao.alarms().firstOrNull { it.id == id }?.model() ?: return@withLock
        val day = epochDay ?: plan.skipEpochDays.maxOrNull() ?: return@withLock
        if (day !in plan.skipEpochDays) return@withLock
        val time = now()
        val zone = zoneId()
        val updated = plan.copy(skipEpochDays = plan.skipEpochDays - day)
        if (plan.days.isEmpty() && (plan.oneShotAt ?: Long.MIN_VALUE) <= time) {
            applyScheduleChanges(listOf(ScheduleChange(updated.copy(enabled = false), null, null)))
            return@withLock
        }
        val existing = runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }
        val occurrence = if (updated.enabled) newOccurrence(updated, time, zone) else null
        val unchanged = existing?.scheduledAt == occurrence?.scheduledAt
        applyScheduleChanges(listOf(ScheduleChange(updated, if (unchanged) null else existing, if (unchanged) null else occurrence)))
    }

    suspend fun disableAll(ids: Set<String>): Int = mutex.withLock {
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val changes = dao.alarms().map { it.model() }.filter { it.id in ids && it.enabled }.map {
            ScheduleChange(it.copy(enabled = false, skipEpochDays = emptySet()), pending[it.id], null)
        }
        applyScheduleChanges(changes)
        changes.size
    }

    private data class ScheduleChange(val plan: AlarmPlan, val previous: AlarmRun?, val next: AlarmRun?)

    private fun newOccurrence(plan: AlarmPlan, timestamp: Long, zone: ZoneId): AlarmRun? =
        Schedule.nextOrNull(plan, Instant.ofEpochMilli(timestamp), zone)?.toEpochMilli()?.let {
            occurrence(plan, it)
        }

    private fun Long.asLocalDate(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    /**
     * Register replacement intents with fresh IDs before changing Room. The engine mutex
     * prevents their receivers from resolving those IDs until the transaction is committed.
     * The old alarms remain registered if any replacement registration fails.
     */
    private suspend fun applyScheduleChanges(changes: List<ScheduleChange>) = withContext(NonCancellable) {
        if (changes.isEmpty()) return@withContext
        val replacements = changes.mapNotNull { it.next }
        if (replacements.isNotEmpty()) {
            check(app.scheduler.allowed()) { "Нет разрешения на точные будильники. Расписание сохранено." }
        }
        val staged = mutableListOf<AlarmRun>()
        try {
            replacements.forEach { next ->
                staged += next
                registerReplacement(next)
            }
            db.withTransaction {
                changes.forEach { change ->
                    dao.putAlarm(change.plan.row())
                    change.previous?.let {
                        // A scheduled placeholder is not a ringing or missed occurrence.
                        dao.deletePendingRun(it.id)
                        dao.deleteScheduledEvent(it.id)
                    }
                    change.next?.let { put(null, it) }
                }
            }
        } catch (e: Exception) {
            staged.forEach { runCatching { app.scheduler.cancel(it) } }
            throw IllegalStateException("Не удалось обновить системное расписание. Прежнее расписание сохранено.", e)
        }
        changes.mapNotNull { it.previous }.forEach { app.scheduler.cancel(it) }
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
            else create(next.plan, Schedule.next(next.plan, Instant.ofEpochMilli(next.scheduledAt), zoneId()).toEpochMilli())
        }; reconcile(before)
    }
    suspend fun event(id: String, token: Int) = mutex.withLock {
        val before = runs()
        val run = before.firstOrNull { it.id == id && it.version == token } ?: return@withLock
        val time = now()
        val next = WakeMachine.due(run, token, time)
        if (next == run) {
            if (run.needsEvent) reconcile(before)
            return@withLock
        }
        db.withTransaction {
            if (run.state != RunState.SCHEDULED) {
                put(run, next)
            } else {
                val plan = dao.alarms().firstOrNull { it.id == run.plan.id }?.model()
                val day = run.scheduledAt.asLocalDate(zoneId()).toEpochDay()
                if (plan?.enabled == true && day !in plan.skipEpochDays &&
                    (plan.days.isEmpty() || plan.lastFiredEpochDay != day)) {
                    put(run, next)
                    val updated = if (plan.days.isEmpty()) plan.copy(enabled = false)
                        else plan.copy(lastFiredEpochDay = day)
                    dao.putAlarm(updated.row())
                    if (updated.enabled) {
                        newOccurrence(updated, time, zoneId())?.let { put(null, it) }
                    }
                } else {
                    dao.deletePendingRun(run.id)
                    dao.deleteScheduledEvent(run.id)
                    if (plan?.enabled == true) {
                        newOccurrence(plan, time, zoneId())?.let { put(null, it) }
                    }
                }
            }
        }
        reconcile(before)
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
                before.filter { it.state == RunState.SCHEDULED && !it.plan.quick }.forEach {
                    dao.deletePendingRun(it.id)
                    dao.deleteScheduledEvent(it.id)
                }
            }
            // Restore the saved date exclusion before creating or re-registering an occurrence.
            dao.alarms().map { it.model() }.filter { it.enabled }.forEach { plan ->
                val pending = runs().firstOrNull { it.plan.id == plan.id && it.state == RunState.SCHEDULED }
                val excluded = pending != null && plan.skipEpochDays.isNotEmpty() &&
                    (plan.days.isEmpty() || pending.scheduledAt.asLocalDate(zoneId()).toEpochDay() in plan.skipEpochDays)
                if (excluded && pending != null) {
                    dao.deletePendingRun(pending.id)
                    dao.deleteScheduledEvent(pending.id)
                }
                if (pending == null || excluded) {
                    newOccurrence(plan, now(), zoneId())?.let { put(null, it) }
                }
            }
        }
        reconcile(before, recoverSound = true)
    }
    suspend fun clearHistory() = mutex.withLock { db.withTransaction { dao.clearHistory(); dao.clearEvents() } }
}

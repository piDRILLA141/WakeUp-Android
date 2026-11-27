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
    private suspend fun runs() = dao.liveRuns().map { it.model() }
    private suspend fun put(previous: AlarmRun?, next: AlarmRun, reason: String = next.state.name) {
        val anchored = if (next.needsEvent && (next.state != RunState.SCHEDULED || next.plan.quick)) next.copy(
            eventElapsed = if (previous?.eventAt == next.eventAt && previous.bootCount == bootCount() && previous.eventElapsed > 0) previous.eventElapsed
                else elapsed() + (next.eventAt - now()), bootCount = bootCount()) else next.copy(eventElapsed = 0)
        val stored = if (next.sounding) {
            val boot = bootCount()
            val deadline = previous?.takeIf { it.sounding && it.bootCount == boot && it.ringingSince == next.ringingSince }
                ?.ringDeadlineElapsed ?: ringExpiresElapsed(next)
            anchored.copy(ringDeadlineElapsed = deadline, bootCount = boot)
        } else anchored.copy(ringDeadlineElapsed = null)
        dao.putRun(stored.row())
        if (previous?.state != next.state || reason != next.state.name) {
            dao.event(EventRow(runId = next.id, at = now(), type = reason))
            val event = when (next.state) {
                RunState.SCHEDULED -> TechnicalEvent.ALARM_SCHEDULED
                RunState.RINGING -> TechnicalEvent.RING_STARTED
                RunState.RE_RINGING -> TechnicalEvent.RE_RING_STARTED
                RunState.WAITING_WAKE_CHECK -> TechnicalEvent.WAKE_CHECK_SCHEDULED
                RunState.WAKE_CHECK_ACTIVE -> TechnicalEvent.WAKE_CHECK_STARTED
                RunState.MISSED -> if (reason == "SESSION_SUPERSEDED") TechnicalEvent.ALARM_SUPERSEDED else if (reason == "BACKUP_ALARM_CANCELLED") TechnicalEvent.BACKUP_ALARM_CANCELLED else TechnicalEvent.ALARM_MISSED
                else -> null
            }
            val journal = app.journal
            if (event != null) journal.recordInTransaction(event, next)
            if (previous?.state == RunState.MISSION_RUNNING &&
                next.state in setOf(RunState.WAITING_WAKE_CHECK, RunState.COMPLETED)) {
                journal.recordInTransaction(TechnicalEvent.MISSION_COMPLETED, next)
            }
            if (next.state == RunState.RE_RINGING) journal.recordInTransaction(TechnicalEvent.WAKE_CHECK_EXPIRED, next)
        }
    }
    private fun replaces(older: AlarmRun, newer: AlarmRun, ungrouped: Boolean) =
        WakeSessions.replaces(older, newer, zoneId(), ungrouped)

    private fun occurrence(plan: AlarmPlan, at: Long) = AlarmRun(UUID.randomUUID().toString(), plan, at)

    private suspend fun create(plan: AlarmPlan, at: Long) = put(null, occurrence(plan, at))

    fun recordAudio(id: String, version: Int, signal: AudioSignal) {
        val event = when (signal) {
            AudioSignal.PLAYBACK_STARTED -> TechnicalEvent.AUDIO_STARTED
            AudioSignal.USING_FALLBACK, AudioSignal.RECOVERY_RETRY -> TechnicalEvent.AUDIO_RECOVERY
            AudioSignal.FOCUS_PAUSED, AudioSignal.FOCUS_LOST, AudioSignal.FOCUS_DELAYED, AudioSignal.CALL_PAUSED -> TechnicalEvent.AUDIO_FOCUS_LOST
            AudioSignal.FOCUS_GAINED -> TechnicalEvent.AUDIO_FOCUS_REGAINED
            AudioSignal.ROUTE_CHANGED -> TechnicalEvent.AUDIO_ROUTE_CHANGED
            else -> TechnicalEvent.AUDIO_ERROR
        }
        app.journal.report(event, id, version, signal.name)
    }

    fun ringExpiresElapsed(run: AlarmRun): Long =
        run.ringDeadlineElapsed?.takeIf { run.bootCount == bootCount() }
            ?: (elapsed() + (WakeMachine.ringExpiresAt(run) - now()).coerceAtMost(WakeMachine.RING_LIMIT_MILLIS))

    private fun expireRing(run: AlarmRun, wallTime: Long): AlarmRun {
        if (!run.sounding || elapsed() < ringExpiresElapsed(run)) return run
        return WakeMachine.expireRing(run, WakeMachine.ringExpiresAt(run)).copy(updatedAt = wallTime)
    }

    suspend fun expireRings() = mutex.withLock {
        val before = runs()
        val time = now()
        val expired = before.map { it to expireRing(it, time) }.filter { (old, new) -> old != new }
        if (expired.isNotEmpty()) {
            db.withTransaction { expired.forEach { (old, new) -> put(old, new) } }
            reconcile(before)
        }
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
    private suspend fun reconcile(before: List<AlarmRun>, restoreSystem: Boolean = false, recoverSound: Boolean = false) {
        val after = runs()
        val oldById = before.associateBy { it.id }
        val currentById = after.associateBy { it.id }
        before.filter { old -> currentById[old.id]?.version != old.version }.forEach(app.scheduler::cancel)
        after.filter { run ->
            (run.needsEvent && (restoreSystem || oldById[run.id]?.version != run.version)) ||
                (recoverSound && run.sounding)
        }.forEach { run ->
            try {
                if (run.needsEvent) app.scheduler.schedule(run)
                else app.scheduler.schedule(run, now() + 1000)
                dao.diagnostic(run.id, "")
            } catch (e: Exception) {
                val message = "Не удалось запланировать: ${e.javaClass.simpleName}"
                dao.diagnostic(run.id, message)
                app.journal.report(if (!app.scheduler.allowed()) TechnicalEvent.PERMISSION_MISSING else TechnicalEvent.SCHEDULE_ERROR,
                    run.id, run.version, if (!app.scheduler.allowed()) "exact_alarm" else "registration")
                AlarmNotifications.failure(app, message)
            }
        }
        AlarmNotifications.refreshChecks(app, after, app.hasVisibleCheckHost())
        val ended = before.filter { it.id !in currentById }
        AlarmNotifications.cancelChecks(app, ended.map { it.id })
        if (ended.any { it.plan.missions.any { spec -> spec.kind == MissionKind.PHOTO } }) prunePhotos()
    }
    suspend fun refreshPresentation() = mutex.withLock { AlarmNotifications.refreshChecks(app, runs(), app.hasVisibleCheckHost()) }

    suspend fun snapshot(): List<AlarmRun> = mutex.withLock { runs() }
    suspend fun profile(key: String): PrivateAlarm? = if (app.unlocked)
        app.privateDb.dao().profile(key)?.let { codec.decodeFromString<PrivateAlarm>(it.json) } else null
    private suspend fun groupAllows(plan: AlarmPlan): Boolean =
        plan.groupId?.let { dao.group(it)?.model()?.enabled } != false

    suspend fun createGroup(name: String, ids: Set<String> = emptySet(), enabled: Boolean = true, cancelBackups: Boolean = false): AlarmGroup = mutex.withLock {
        val title = name.trim()
        require(title.length in 1..60) { "Название группы должно содержать от 1 до 60 символов" }
        val group = AlarmGroup(UUID.randomUUID().toString(), enabled, cancelBackups)
        app.privateDb.dao().putProfile(ProfileRow(group.id, codec.encodeToString(PrivateAlarm(title = title))))
        val members = dao.alarms().map { it.model() }.filter { it.id in ids && !it.quick }
        changeMembership(members, group) { dao.putGroup(group.row()) }
        app.journal.report(TechnicalEvent.GROUP_CREATED, group.id)
        group
    }

    suspend fun editGroup(id: String, name: String, cancelBackups: Boolean) = mutex.withLock {
        val group = dao.group(id)?.model() ?: error("Группа не найдена")
        val title = name.trim()
        require(title.length in 1..60) { "Название группы должно содержать от 1 до 60 символов" }
        app.privateDb.dao().putProfile(ProfileRow(id, codec.encodeToString(PrivateAlarm(title = title))))
        dao.putGroup(group.copy(cancelBackups = cancelBackups).row())
    }

    suspend fun moveAlarms(ids: Set<String>, target: String?) = mutex.withLock {
        val group = target?.let { dao.group(it)?.model() ?: error("Группа не найдена") }
        val members = dao.alarms().map { it.model() }.filter { it.id in ids && !it.quick && it.groupId != target }
        changeMembership(members, group)
        app.journal.report(TechnicalEvent.GROUP_SCHEDULE_CHANGED, target)
    }

    suspend fun setGroupEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val group = dao.group(id)?.model() ?: return@withLock
        if (group.enabled == enabled) return@withLock
        val updated = group.copy(enabled = enabled)
        changeMembership(dao.alarms().map { it.model() }.filter { it.groupId == id }, updated) {
            dao.putGroup(updated.row())
        }
        app.journal.report(TechnicalEvent.GROUP_SCHEDULE_CHANGED, id)
    }

    suspend fun removeGroup(id: String, deleteAlarms: Boolean) = mutex.withLock {
        val group = dao.group(id)?.model() ?: return@withLock
        val plans = dao.alarms().map { it.model() }.filter { it.groupId == group.id }
        if (deleteAlarms) {
            val before = runs()
            val ids = plans.mapTo(mutableSetOf()) { it.id }
            db.withTransaction {
                before.filter { it.plan.id in ids }.forEach { put(it, WakeMachine.cancel(it, now())) }
                ids.forEach { dao.deleteAlarm(it) }
                dao.deleteGroup(id)
            }
            reconcile(before)
            if (plans.any { it.missions.any { mission -> mission.kind == MissionKind.PHOTO } }) prunePhotos()
        } else {
            changeMembership(plans, null) { dao.deleteGroup(id) }
        }
        app.journal.report(TechnicalEvent.GROUP_SCHEDULE_CHANGED, id)
    }

    private suspend fun changeMembership(plans: List<AlarmPlan>, group: AlarmGroup?, persistGroup: suspend () -> Unit = {}) {
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val kept = mutableListOf<AlarmRun>()
        val changes = plans.map { original ->
            val previous = pending[original.id]
            val shouldSchedule = original.enabled && group?.enabled != false
            val plan = original.copy(groupId = group?.id,
                oneShotAt = original.oneShotAt ?: previous?.scheduledAt?.takeIf { !shouldSchedule && original.days.isEmpty() })
            if (shouldSchedule && previous != null) {
                if (previous.plan.groupId != plan.groupId) kept += previous.copy(plan = previous.plan.copy(groupId = plan.groupId))
                ScheduleChange(plan, null, null)
            } else {
                ScheduleChange(plan, previous, if (shouldSchedule) newOccurrence(plan, now(), zoneId()) else null)
            }
        }
        try {
            applyScheduleChanges(changes) {
                persistGroup()
                kept.forEach { dao.putRun(it.row()) }
            }
        } catch (error: IllegalStateException) {
            app.journal.report(TechnicalEvent.GROUP_OPERATION_FAILED, group?.id, code = "registration")
            throw error
        }
    }

    private suspend fun prunePhotos() {
        if (!app.unlocked) return
        try {
            val used = dao.alarms().mapTo(HashSet()) { it.model().privateKey }
            runs().mapTo(used) { it.plan.privateKey }
            app.photoReferences.removeUnused(used)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { app.journal.report(TechnicalEvent.PHOTO_STORAGE_ERROR) }
    }

    suspend fun save(plan: AlarmPlan, profile: PrivateAlarm, testAt: Long? = null,
        photoDraft: PhotoReference? = null) = mutex.withLock {
        plan.validate()
        require(!plan.quick || plan.groupId == null) { "Быстрый будильник не входит в группу" }
        require(plan.groupId?.let { dao.group(it) != null } != false) { "Группа не найдена" }
        if (plan.enabled && groupAllows(plan) && !app.scheduler.allowed()) {
            app.journal.report(TechnicalEvent.PERMISSION_MISSING, code = "exact_alarm")
            error("Разрешите точные будильники в настройках Android")
        }
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
            automaticSkipDay = if (oldPlan == null) plan.automaticSkipDay else oldPlan.automaticSkipDay,
            groupSkipDays = oldPlan?.groupSkipDays ?: plan.groupSkipDays,
        )
        val previous = runs().firstOrNull { it.plan.id == saved.id && it.state == RunState.SCHEDULED }
        val next = if (saved.enabled && groupAllows(saved)) {
            if (testAt != null) occurrence(saved, testAt) else newOccurrence(saved, now(), zoneId())
        } else null
        val hasPhoto = saved.missions.any { it.kind == MissionKind.PHOTO }
        val photoChanged = (hasPhoto && photoDraft != null) || oldPlan?.missions?.any { it.kind == MissionKind.PHOTO } == true
        try {
            val photoId = if (!hasPhoto) null else if (photoDraft != null) app.photoReferences.save(photoDraft) else profile.photoId
            app.privateDb.dao().putProfile(ProfileRow(saved.privateKey, codec.encodeToString(profile.copy(photoId = photoId))))
            applyScheduleChanges(listOf(ScheduleChange(saved, previous, next, keepCancelled = true)))
            if (hasPhoto && photoDraft != null) app.journal.report(TechnicalEvent.PHOTO_REFERENCE_SAVED)
        } finally {
            if (photoChanged) withContext(NonCancellable) { prunePhotos() }
        }
    }
    suspend fun duplicateLater(id: String): AlarmPlan {
        val original = mutex.withLock { dao.alarms().first { it.id == id }.model() }
        val at = mutex.withLock { runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }?.scheduledAt }
        val copy = AlarmShortcuts.copyForNext(original.copy(skipEpochDays = emptySet(), lastFiredEpochDay = null, automaticSkipDay = null, groupSkipDays = emptyMap()), UUID.randomUUID().toString(), Instant.ofEpochMilli(now()), zoneId(),at)
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
    // Compatibility with unversioned intents from an older installed build.
    suspend fun confirmCheck(id: String, answer: String = "", expectedVersion: Int? = null) {
        val run = dao.run(id)?.model() ?: return
        if (run.state != RunState.WAKE_CHECK_ACTIVE) return
        check(expectedVersion == null || run.version == expectedVersion) { "Эта проверка уже изменилась" }
        when (confirmWake(run.checkKey, answer, expectedVersion)) {
            CheckConfirmation.EXPIRED -> error("Время подтверждения истекло")
            CheckConfirmation.WRONG_ANSWER -> error("Проверьте ответ")
            CheckConfirmation.CHANGED -> error("Эта проверка уже изменилась")
            else -> Unit
        }
    }

    suspend fun confirmWake(key: CheckKey, answer: String = "", expectedVersion: Int? = null): CheckConfirmation = mutex.withLock {
        val before = runs()
        val run = before.firstOrNull { it.id == key.runId } ?: dao.run(key.runId)?.model()
            ?: return@withLock CheckConfirmation.MISSING
        val time = now()
        when (checkPage(key, listOf(run), time)) {
            CheckPage.CONFIRMED -> return@withLock CheckConfirmation.ALREADY_CONFIRMED
            CheckPage.EXPIRED, CheckPage.RE_RING -> return@withLock CheckConfirmation.EXPIRED
            CheckPage.ACTIVE -> Unit
            else -> return@withLock CheckConfirmation.CHANGED
        }
        if (expectedVersion != null && run.version != expectedVersion) return@withLock CheckConfirmation.CHANGED
        val passed = Missions.math(run.seed + run.checkIndex + run.cycle, 1).answer == answer.toIntOrNull()
        val confirmed = WakeMachine.confirm(run, time, passed)
        if (confirmed == run) return@withLock CheckConfirmation.WRONG_ANSWER
        completeTransition(before, run, confirmed, finalWakeCheck = true)
        CheckConfirmation.ACCEPTED
    }
    suspend fun cancelCheck(key: CheckKey) = mutex.withLock {
        val before = runs()
        val run = before.firstOrNull { it.id == key.runId && key.matches(it) } ?: return@withLock
        val time = now()
        if (checkPage(key, listOf(run), time) != CheckPage.ACTIVE) return@withLock
        db.withTransaction { put(run, WakeMachine.cancel(run, time, emergency = true)) }
        reconcile(before)
    }

    suspend fun enable(id: String, enabled: Boolean) {
        if (!enabled) {
            disableAll(setOf(id))
            return
        }
        mutex.withLock {
            val plan = dao.alarms().firstOrNull { it.id == id }?.model() ?: return@withLock
            if (plan.enabled) return@withLock
            val updated = plan.copy(enabled = true, skipEpochDays = emptySet(), groupSkipDays = emptyMap(),
                oneShotAt = plan.oneShotAt?.takeIf { it > now() })
            applyScheduleChanges(listOf(ScheduleChange(updated, null, if (groupAllows(updated)) newOccurrence(updated, now(), zoneId()) else null)))
        }
    }

    data class BulkResult(val changed: Int, val notScheduled: Int, val alreadySkipped: Int = 0)

    suspend fun skipTomorrow(ids: Set<String>, allowOneShot: Boolean, groupId: String? = null): BulkResult = mutex.withLock {
        val timestamp = now()
        val zone = zoneId()
        val tomorrow = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().plusDays(1)
        val plans = dao.alarms().map { it.model() }.filter { if (groupId != null) it.groupId == groupId else it.id in ids }
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val changed = mutableListOf<ScheduleChange>()
        var unavailable = if (groupId == null) ids.size - plans.size else 0
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
                        groupSkipDays = plan.groupSkipDays.filterKeys { it >= tomorrow.minusDays(1).toEpochDay() } +
                            (if (groupId == null) emptyMap() else mapOf(tomorrow.toEpochDay() to groupId)),
                        oneShotAt = if (plan.days.isEmpty()) existing?.scheduledAt ?: plan.oneShotAt else null,
                    )
                    val keep = existing?.takeIf { it.scheduledAt.asLocalDate(zone) != tomorrow }
                    val replacement = if (keep != null || !groupAllows(updated)) null else newOccurrence(updated, timestamp, zone)
                    changed += ScheduleChange(updated, if (keep == null) existing else null, replacement)
                }
            }
        }
        applyScheduleChanges(changed)
        if (groupId != null && changed.isNotEmpty()) app.journal.report(TechnicalEvent.GROUP_SKIPPED, groupId)
        BulkResult(changed.size, unavailable, already)
    }

    suspend fun undoSkip(id: String, epochDay: Long? = null) = mutex.withLock {
        val plan = dao.alarms().firstOrNull { it.id == id }?.model() ?: return@withLock
        val day = epochDay ?: plan.skipEpochDays.maxOrNull() ?: return@withLock
        val pending = runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }
        undoSkipChange(plan, pending, day, now(), zoneId())?.let { applyScheduleChanges(listOf(it)) }
    }

    suspend fun undoGroupSkip(id: String): Int = mutex.withLock {
        val time = now()
        val zone = zoneId()
        val tomorrow = time.asLocalDate(zone).plusDays(1).toEpochDay()
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val changes = dao.alarms().map { it.model() }.filter { it.groupId == id && it.groupSkipDays[tomorrow] == id }
            .mapNotNull { undoSkipChange(it, pending[it.id], tomorrow, time, zone) }
        if (changes.isNotEmpty()) {
            applyScheduleChanges(changes)
            app.journal.report(TechnicalEvent.GROUP_SKIP_RESTORED, id)
        }
        changes.size
    }

    private suspend fun undoSkipChange(plan: AlarmPlan, existing: AlarmRun?, day: Long, time: Long, zone: ZoneId): ScheduleChange? {
        if (day !in plan.skipEpochDays) return null
        val updated = plan.copy(skipEpochDays = plan.skipEpochDays - day, groupSkipDays = plan.groupSkipDays - day)
        if (plan.days.isEmpty() && (plan.oneShotAt ?: Long.MIN_VALUE) <= time) {
            return ScheduleChange(updated.copy(enabled = false), existing, null)
        }
        val occurrence = if (updated.enabled && groupAllows(updated)) newOccurrence(updated, time, zone) else null
        val unchanged = existing?.scheduledAt == occurrence?.scheduledAt
        return ScheduleChange(updated, if (unchanged) null else existing, if (unchanged) null else occurrence)
    }

    suspend fun disableAll(ids: Set<String>): Int = mutex.withLock {
        val pending = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
        val changes = dao.alarms().map { it.model() }.filter { it.id in ids && it.enabled }.map {
            ScheduleChange(it.copy(enabled = false, skipEpochDays = emptySet(), groupSkipDays = emptyMap()), pending[it.id], null)
        }
        applyScheduleChanges(changes)
        changes.size
    }

    private data class ScheduleChange(
        val plan: AlarmPlan,
        val previous: AlarmRun?,
        val next: AlarmRun?,
        val keepCancelled: Boolean = false,
        val automaticallySkipped: Boolean = false,
    )

    private fun newOccurrence(plan: AlarmPlan, timestamp: Long, zone: ZoneId): AlarmRun? =
        Schedule.nextOrNull(plan, Instant.ofEpochMilli(timestamp), zone)?.toEpochMilli()?.takeIf { it > timestamp }?.let {
            occurrence(plan, it)
        }

    private fun Long.asLocalDate(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    /**
     * Register replacement intents with fresh IDs before changing Room. The engine mutex
     * prevents their receivers from resolving those IDs until the transaction is committed.
     * The old alarms remain registered if any replacement registration fails.
     */
    private suspend fun applyScheduleChanges(changes: List<ScheduleChange>, commit: suspend () -> Unit = {}) = withContext(NonCancellable) {
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
                        if (change.automaticallySkipped) {
                            put(it, it.copy(state = RunState.MISSED, version = it.version + 1,
                                updatedAt = now(), disposition = "backup"), "BACKUP_ALARM_CANCELLED")
                        } else if (change.keepCancelled) {
                            put(it, WakeMachine.cancel(it, now()))
                        } else {
                            // A skipped placeholder is not a ringing or missed occurrence.
                            dao.deletePendingRun(it.id)
                            dao.deleteScheduledEvent(it.id)
                        }
                    }
                    change.next?.let { put(null, it) }
                }
                commit()
            }
        } catch (e: Exception) {
            staged.forEach { runCatching { app.scheduler.cancel(it) } }
            val failed = staged.lastOrNull()
            app.journal.report(TechnicalEvent.SCHEDULE_ERROR, failed?.id, failed?.version, "registration")
            throw IllegalStateException("Не удалось обновить системное расписание. Прежнее расписание сохранено.", e)
        }
        changes.mapNotNull { it.previous }.forEach { app.scheduler.cancel(it) }
    }
    suspend fun delete(id: String) = mutex.withLock {
        val hadPhoto = dao.alarms().firstOrNull { it.id == id }?.model()
            ?.missions?.any { it.kind == MissionKind.PHOTO } == true
        val before = runs()
        db.withTransaction {
            before.filter { it.plan.id == id && !it.terminal }.forEach { put(it, WakeMachine.cancel(it, now())) }
            dao.deleteAlarm(id)
        }; reconcile(before)
        if (hadPhoto) prunePhotos()
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
        val next = expireRing(WakeMachine.due(run, token, time), time)
        if (next == run) {
            if (run.needsEvent) app.scheduler.schedule(run)
            return@withLock
        }
        db.withTransaction {
            app.journal.recordInTransaction(TechnicalEvent.ALARM_DELIVERED, run)
            if (run.state != RunState.SCHEDULED) {
                put(run, next)
            } else {
                val plan = dao.alarms().firstOrNull { it.id == run.plan.id }?.model()
                val day = run.scheduledAt.asLocalDate(zoneId()).toEpochDay()
                if (plan?.enabled == true && groupAllows(plan) && day !in plan.skipEpochDays && day != plan.automaticSkipDay &&
                    (plan.days.isEmpty() || plan.lastFiredEpochDay != day)) {
                    val ungrouped = app.settings.deduplicateEnabled()
                    val recent = if (next.sounding) dao.completedNear(run.scheduledAt,
                        time + 86_400_000L).map { it.model() } else emptyList()
                    val replaced = next.sounding && (before + recent).any { existing ->
                        existing.firedAt > 0 && !existing.emergency && replaces(run, existing, ungrouped)
                    }
                    if (replaced) {
                        put(run, WakeMachine.supersede(run, time), "SESSION_SUPERSEDED")
                    } else {
                        if (next.sounding) before.filter { previous ->
                            previous.firedAt > 0 && replaces(previous, next, ungrouped)
                        }.forEach { previous ->
                            put(previous, WakeMachine.supersede(previous, time), "SESSION_SUPERSEDED")
                        }
                        put(run, next)
                    }
                    val updated = if (plan.days.isEmpty()) plan.copy(enabled = false)
                        else plan.copy(lastFiredEpochDay = day)
                    dao.putAlarm(updated.row())
                    if (updated.enabled) {
                        newOccurrence(updated, time, zoneId())?.let { put(null, it) }
                    }
                } else {
                    dao.deletePendingRun(run.id)
                    dao.deleteScheduledEvent(run.id)
                    if (plan?.enabled == true && groupAllows(plan)) {
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
        if (changed == run) return@withLock
        completeTransition(before, run, changed, finalWakeCheck = false)
    }

    suspend fun cancelledBackups(runId: String): Int = dao.run(runId)?.model()?.cancelledBackups ?: 0

    private suspend fun completeTransition(before: List<AlarmRun>, previous: AlarmRun, next: AlarmRun, finalWakeCheck: Boolean) {
        // Only the final accepted check or successful completion of all missions can cancel backups.
        val reachedGoal = next.state == RunState.COMPLETED &&
            (finalWakeCheck || (!previous.plan.wakeCheck && previous.state == RunState.MISSION_RUNNING))
        val group = if (reachedGoal && !previous.plan.independent) previous.plan.groupId?.let { dao.group(it)?.model() } else null
        val backups = if (group?.enabled == true &&
            group.cancelBackups) backupChanges(before, previous) else emptyList()
        if (backups.isNotEmpty()) {
            val applied = try {
                applyScheduleChanges(backups) {
                    put(previous, next.copy(cancelledBackups = backups.size))
                    if (finalWakeCheck) app.journal.recordInTransaction(TechnicalEvent.WAKE_CHECK_CONFIRMED, previous)
                    app.journal.recordInTransaction(TechnicalEvent.GROUP_WAKE_CONFIRMED, previous)
                }
                true
            } catch (_: IllegalStateException) {
                app.journal.report(TechnicalEvent.GROUP_OPERATION_FAILED, group?.id, code = "registration")
                false
            }
            if (applied) {
                val oldBackups = backups.mapNotNull { it.previous?.id }.toSet()
                reconcile(before.filterNot { it.id in oldBackups } + backups.mapNotNull { it.next })
                return
            }
        }
        db.withTransaction {
            put(previous, next)
            if (finalWakeCheck) app.journal.recordInTransaction(TechnicalEvent.WAKE_CHECK_CONFIRMED, previous)
        }
        reconcile(before)
    }

    private suspend fun backupChanges(before: List<AlarmRun>, confirmed: AlarmRun): List<ScheduleChange> {
        val time = now()
        val zone = zoneId()
        val plans = dao.alarms().map { it.model() }.associateBy { it.id }
        return before.mapNotNull { pending ->
            val plan = plans[pending.plan.id] ?: return@mapNotNull null
            if (pending.state != RunState.SCHEDULED || !plan.enabled || plan.id == confirmed.plan.id ||
                plan.quick || plan.independent || plan.groupId != confirmed.plan.groupId ||
                pending.scheduledAt <= time || pending.scheduledAt <= confirmed.scheduledAt ||
                !WakeSessions.inWindow(confirmed.scheduledAt, pending.scheduledAt, zone)) return@mapNotNull null
            val updated = plan.copy(automaticSkipDay = pending.scheduledAt.asLocalDate(zone).toEpochDay(),
                oneShotAt = if (plan.days.isEmpty()) pending.scheduledAt else null)
            ScheduleChange(updated, pending, newOccurrence(updated, time, zone), automaticallySkipped = true)
        }
    }

    suspend fun restoreBackup(id: String) = mutex.withLock {
        val plan = dao.alarms().firstOrNull { it.id == id }?.model() ?: error("Будильник не найден")
        val day = plan.automaticSkipDay ?: error("Отменённого резервного сигнала нет")
        require(plan.enabled && groupAllows(plan)) { "Сначала включите будильник и его группу" }
        require(day !in plan.skipEpochDays) { "На эту дату установлен ручной пропуск" }
        val zone = zoneId()
        val at = if (plan.days.isEmpty()) plan.oneShotAt else LocalDate.ofEpochDay(day)
            .atTime(plan.hour, plan.minute).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        require(at != null && at > now()) { "Время отменённого сигнала уже прошло" }
        val updated = plan.copy(automaticSkipDay = null)
        val pending = runs().firstOrNull { it.plan.id == id && it.state == RunState.SCHEDULED }
        val replacement = if (plan.enabled && groupAllows(updated)) newOccurrence(updated, now(), zone) else null
        applyScheduleChanges(listOf(ScheduleChange(updated, pending, replacement)))
        app.journal.report(TechnicalEvent.BACKUP_ALARM_RESTORED, id)
    }

    suspend fun restore(recalculate: Boolean = false, recoverSound: Boolean = true) = mutex.withLock {
        val before = runs()
        db.withTransaction {
            val ungrouped = app.settings.deduplicateEnabled()
            val fired = before.filter { it.firedAt > 0 }
            before.forEach { run ->
                if (run.firedAt > 0 && fired.any { replaces(run, it, ungrouped) }) {
                    put(run, WakeMachine.supersede(run, now()), "SESSION_SUPERSEDED")
                    return@forEach
                }
                if (run.sounding) {
                    val expired = expireRing(run, now())
                    if (expired != run || run.ringDeadlineElapsed == null || run.bootCount != bootCount()) {
                        put(run, expired)
                    }
                }
            }
            if (recalculate) {
                // A manually changed wall clock must not turn a three-minute check into an hour.
                runs().filter { it.needsEvent && (it.state != RunState.SCHEDULED || it.plan.quick) && it.bootCount == bootCount() && it.eventElapsed > 0 }.forEach { run ->
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
            val scheduled = runs().filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
            dao.alarms().map { it.model() }.filter { it.enabled }.forEach { plan ->
                if (!groupAllows(plan)) {
                    scheduled[plan.id]?.let { dao.deletePendingRun(it.id); dao.deleteScheduledEvent(it.id) }
                    return@forEach
                }
                val pending = scheduled[plan.id]
                val excluded = pending != null && (pending.scheduledAt.asLocalDate(zoneId()).toEpochDay() == plan.automaticSkipDay ||
                    (plan.skipEpochDays.isNotEmpty() && (plan.days.isEmpty() ||
                        pending.scheduledAt.asLocalDate(zoneId()).toEpochDay() in plan.skipEpochDays)))
                if (excluded && pending != null) {
                    dao.deletePendingRun(pending.id)
                    dao.deleteScheduledEvent(pending.id)
                }
                if (pending == null || excluded) {
                    newOccurrence(plan, now(), zoneId())?.let { put(null, it) }
                }
            }
        }
        reconcile(before, restoreSystem = true, recoverSound = recoverSound)
        prunePhotos()
        app.journal.report(TechnicalEvent.SCHEDULE_RESTORED)
    }
    suspend fun clearHistory() = mutex.withLock { db.withTransaction { dao.clearHistory(); dao.clearEvents() } }
}

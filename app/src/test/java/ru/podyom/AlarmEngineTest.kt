package ru.podyom

import android.app.AlarmManager
import android.os.UserManager
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import ru.podyom.alarm.AlarmEngine
import ru.podyom.core.*
import ru.podyom.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class AlarmEngineTest {
    private lateinit var app: App
    private lateinit var engine: AlarmEngine
    private var clock = System.currentTimeMillis()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        Shadows.shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        engine = AlarmEngine(app, clock = { clock })
    }
    @After fun close() { app.runtime.close(); if(app.unlocked) app.privateDb.close() }
    private suspend fun runs() = app.runtime.dao().runs().map {it.model()}
    private fun plan(id:String="a") = AlarmPlan(id, missions=listOf(MissionSpec(count=1)))
    @Test fun createEditAndDeleteReconcileSystemAlarm() = runBlocking {
        engine.save(plan(),PrivateAlarm(),clock+60_000)
        val first=runs().single(); assertNotNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
        engine.save(plan().copy(hour=8),PrivateAlarm(title="Изменён"),clock+120_000)
        assertEquals(RunState.CANCELLED,runs().first {it.id==first.id}.state)
        assertEquals(1,runs().count {it.state==RunState.SCHEDULED})
        engine.delete("a"); assertTrue(runs().all {it.terminal})
        assertNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
    }
    @Test fun oneShotIsConsumedAndDuplicateDeliveryDoesNotRingTwice() = runBlocking {
        engine.save(plan(),PrivateAlarm(),clock+1000);val r=runs().single();clock+=1000
        engine.event(r.id,r.version);engine.event(r.id,r.version)
        assertEquals(1,runs().size);assertEquals(RunState.RINGING,runs().single().state)
        assertFalse(app.runtime.dao().alarms().single().model().enabled)
    }
    @Test fun recurringAlarmSurvivesCurrentWakeCheck() = runBlocking {
        engine.save(plan().copy(days=(1..7).toSet()),PrivateAlarm(),clock+1000)
        val r=runs().single();clock+=1000;engine.event(r.id,0)
        assertEquals(1,runs().count {it.state==RunState.SCHEDULED})
        engine.update(r.id,WakeMachine::startMission)
        engine.update(r.id) {run,t->WakeMachine.progress(run,run.version,t)}
        val waiting=runs().first {it.id==r.id};assertEquals(RunState.WAITING_WAKE_CHECK,waiting.state)
        clock=waiting.eventAt;engine.event(r.id,waiting.version)
        val active=runs().first {it.id==r.id};clock=active.deadline;engine.event(r.id,active.version)
        assertEquals(RunState.RE_RINGING,runs().first {it.id==r.id}.state)
        assertEquals(1,runs().count {it.state==RunState.SCHEDULED})
    }
    @Test fun persistedCheckRestoredByFreshCoordinator() = runBlocking {
        engine.save(plan(),PrivateAlarm(),clock+1000);val r=runs().single();clock+=1000;engine.event(r.id,0)
        engine.update(r.id,WakeMachine::startMission);engine.update(r.id) {a,t->WakeMachine.progress(a,a.version,t)}
        val saved=runs().single()
        val reopened=RuntimeDatabase.open(app)
        assertEquals(saved,reopened.dao().run(r.id)!!.model());reopened.close()
        app.scheduler.cancel(saved)
        AlarmEngine(app, clock = {clock}).restore()
        assertEquals(saved.eventAt,app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        clock=saved.eventAt;engine.event(saved.id,saved.version)
        engine.update(r.id) {a,t->WakeMachine.confirm(a,t,false)}
        assertEquals(RunState.COMPLETED,runs().single().state)
        assertNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
    }
    @Test fun parallelAlarmsRemainIndependent() = runBlocking {
        engine.save(plan("a").copy(independent = true),PrivateAlarm(),clock+1000);engine.save(plan("b").copy(independent = true),PrivateAlarm(),clock+1000)
        val initial=runs();clock+=1000;initial.forEach {engine.event(it.id,it.version)}
        assertEquals(2,runs().count {it.sounding})
        engine.update(initial.first().id) {a,t->WakeMachine.cancel(a,t,true)}
        assertEquals(1,runs().count {it.sounding})
    }
    @Test fun skipPreservesWeeklyScheduleAndDisableCancelsPending() = runBlocking {
        engine.save(plan().copy(days=(1..7).toSet()),PrivateAlarm(),clock+1000)
        val first=runs().single();engine.skip("a")
        assertEquals(RunState.CANCELLED,runs().first {it.id==first.id}.state)
        assertEquals(1,runs().count {it.state==RunState.SCHEDULED})
        engine.enable("a",false);assertTrue(runs().all {it.terminal})
    }
    @Test fun editsCannotChangePrivateSnapshotOfActiveRun() = runBlocking {
        engine.save(plan(),PrivateAlarm(qr="original"),clock+1000);val first=runs().single();clock+=1000;engine.event(first.id,0)
        engine.save(plan(),PrivateAlarm(qr="replacement"),clock+60_000)
        assertEquals("original",engine.profile(first.plan.privateKey)!!.qr)
    }
    @Test fun directBootDoesNotReadCredentialStorage() = runBlocking {
        engine.save(plan(),PrivateAlarm(title="Private",qr="secret"),clock+1000)
        Shadows.shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertNull(engine.profile(runs().single().plan.privateKey))
        assertFalse(app.runtime.dao().runs().single().json.contains("secret"))
        engine.restore()
        Shadows.shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }
    @Test fun missingExactPermissionCannotPretendToSaveEnabledAlarm() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {engine.save(plan(),PrivateAlarm());fail("Must reject enabled alarm")} catch (_:IllegalStateException) { }
        assertTrue(runs().isEmpty())
    }
    @Test fun manualClockChangeKeepsWakeDelayMonotonic() = runBlocking {
        var monotonic=10_000L
        val timed=AlarmEngine(app, {clock}, {monotonic})
        timed.save(plan(),PrivateAlarm(),clock+1000); val r=runs().single();clock+=1000;monotonic+=1000
        timed.event(r.id,0);timed.update(r.id,WakeMachine::startMission)
        timed.update(r.id) {a,t->WakeMachine.progress(a,a.version,t)}
        monotonic+=60_000;clock-=3_600_000
        timed.restore(recalculate=true)
        val rebased=runs().first {it.id==r.id}
        assertEquals(120_000L,rebased.eventAt-clock)
    }

    @Test fun duplicateRegistersNewActiveOccurrenceAndPreservesPrivateData() = runBlocking {
        engine.save(plan().copy(hour=6,minute=30),PrivateAlarm(title="Школа",qr="own",phrase="Моя фраза"))
        val original=app.runtime.dao().alarms().single().model()
        val copy=engine.duplicateLater("a")
        assertEquals(6,copy.hour);assertEquals(40,copy.minute);assertTrue(copy.enabled);assertNotEquals(original.id,copy.id)
        assertEquals(original,app.runtime.dao().alarms().first {it.id=="a"}.model())
        assertEquals(engine.profile(original.privateKey),engine.profile(app.runtime.dao().alarms().first {it.id==copy.id}.model().privateKey))
        assertEquals(2,runs().count {it.state==RunState.SCHEDULED})
        assertEquals(runs().filter {it.needsEvent}.minOf {it.eventAt},app.scheduler.nextSystemAlarm()!!.triggerTime)
    }
    @Test fun quickIsDebouncedSurvivesRestartAndDoesNotRepeat() = runBlocking {
        val created=engine.quick()!!
        assertNull(engine.quick());assertEquals(clock+1_200_000,created.oneShotAt)
        assertEquals(1,runs().size)
        val initial=runs().single();app.scheduler.cancel(initial)
        AlarmEngine(app,clock={clock}).restore()
        assertEquals(created.oneShotAt,app.scheduler.nextSystemAlarm()!!.triggerTime)
        clock=created.oneShotAt!!;engine.event(initial.id,initial.version)
        engine.update(initial.id,WakeMachine::dismissQuick)
        assertEquals(RunState.COMPLETED,runs().single().state)
        engine.restore();assertEquals(1,runs().size);assertNull(app.scheduler.nextSystemAlarm())
    }
    @Test fun quickTimerRebasesWallClockWithoutChangingElapsedDelay() = runBlocking {
        var monotonic=10_000L
        val timed=AlarmEngine(app,{clock},{monotonic})
        timed.quick();monotonic+=60_000;clock-=3_600_000
        timed.restore(recalculate=true)
        val stored=runs().single()
        assertEquals(1_140_000L,stored.eventAt-clock)
        assertEquals(stored.eventAt,app.runtime.dao().alarms().single().model().oneShotAt)
    }
    @Test fun systemShowIntentPointsToOurScheduleAndDeletingOneKeepsOther() = runBlocking {
        engine.save(plan("a"),PrivateAlarm(),clock+60_000)
        engine.save(plan("b"),PrivateAlarm(),clock+120_000)
        val show=app.scheduler.nextSystemAlarm()!!.showIntent
        val intent=Shadows.shadowOf(show).savedIntent
        assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS,intent.action)
        assertEquals("ru.podyom.ui.MainActivity",intent.component!!.className)
        engine.delete("a")
        assertEquals(clock+120_000,app.scheduler.nextSystemAlarm()!!.triggerTime)
    }
    @Test fun oldJsonRowsRestoreWithoutSchemaChangeOrDataLoss() = runBlocking {
        app.runtime.dao().putAlarm(AlarmRow("legacy","""{"id":"legacy","hour":6,"minute":40}"""))
        app.privateDb.dao().putProfile(ProfileRow("legacy",codec.encodeToString(PrivateAlarm(title="Старый будильник"))))
        engine.restore()
        assertEquals("legacy",runs().single().plan.id);assertEquals(6,runs().single().plan.hour)
        assertEquals("Старый будильник",engine.profile("legacy")!!.title)
        val settings=codec.decodeFromString<AppSettings>("""{"light":true,"clock24":false,"readySeen":true}""")
        assertTrue(settings.light);assertFalse(settings.clock24);assertFalse(settings.enhancedCheck)
    }

    private val testZone = ZoneId.of("Europe/Moscow")

    private fun monday(hour: Int): LocalDate {
        val date = Instant.ofEpochMilli(clock).atZone(testZone).toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        clock = date.atTime(hour, 0).atZone(testZone).toInstant().toEpochMilli()
        return date
    }

    private fun instant(day: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = testZone) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun daily(id: String, minute: Int = 0) =
        plan(id).copy(hour = 6, minute = minute, days = (1..7).toSet())

    @Test fun bulkSkipRestoresAfterBootAndUndoRegistersTomorrowAgain() = runBlocking {
        val date = monday(7)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        val ids = (0..3).map { "alarm-$it" }.toSet()
        ids.forEachIndexed { index, id ->
            coordinator.save(daily(id, index * 10), PrivateAlarm(title = "Исходный"))
        }
        val prior = runs()
        val result = coordinator.skipTomorrow(ids, allowOneShot = false)
        assertEquals(4, result.changed)
        assertTrue(runs().all { it.state == RunState.SCHEDULED &&
            Instant.ofEpochMilli(it.eventAt).atZone(testZone).toLocalDate() == date.plusDays(2) })
        assertTrue(prior.none { old -> runs().any { it.id == old.id } })
        runs().forEach(app.scheduler::cancel)
        AlarmEngine(app, clock = { clock }, zoneId = { testZone }).restore()
        assertEquals(instant(date.plusDays(2), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
        coordinator.undoSkip("alarm-0")
        assertEquals(instant(date.plusDays(1), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
        val persisted = app.runtime.dao().alarms().map { it.model() }
        assertTrue(persisted.all { it.enabled })
        assertEquals("Исходный", coordinator.profile(persisted.first().privateKey)!!.title)
        assertEquals(3, persisted.count { date.plusDays(1).toEpochDay() in it.skipEpochDays })
    }

    @Test fun skipPreservesTodayAndReportsNonMatchingAlarms() = runBlocking {
        val date = monday(5)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        coordinator.save(daily("two").copy(days = setOf(1, 2)), PrivateAlarm())
        coordinator.save(daily("weekend").copy(days = setOf(6, 7)), PrivateAlarm())
        val today = runs().first { it.plan.id == "two" }
        val result = coordinator.skipTomorrow(setOf("two", "weekend"), allowOneShot = false)
        assertEquals(1, result.changed)
        assertEquals(1, result.notScheduled)
        assertEquals(today.id, runs().first { it.plan.id == "two" }.id)
        clock = instant(date, 6)
        coordinator.event(today.id, today.version)
        assertEquals(instant(date.plusWeeks(1), 6), runs().single {
            it.plan.id == "two" && it.state == RunState.SCHEDULED
        }.eventAt)
    }

    @Test fun fridayWeekdaysAreUnaffectedAndOneShotRequiresConfirmation() = runBlocking {
        val friday = monday(7).plusDays(4)
        clock = instant(friday, 7)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        coordinator.save(daily("weekdays").copy(days = (1..5).toSet()), PrivateAlarm())
        coordinator.save(plan("single").copy(hour = 6), PrivateAlarm())
        val old = runs()
        assertTrue(runCatching { coordinator.skipTomorrow(setOf("weekdays", "single"), false) }.isFailure)
        assertEquals(old.toSet(), runs().toSet())
        val result = coordinator.skipTomorrow(setOf("weekdays", "single"), true)
        assertEquals(1, result.changed)
        assertEquals(1, result.notScheduled)
        assertEquals(instant(friday.plusDays(3), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
        coordinator.undoSkip("single")
        assertEquals(instant(friday.plusDays(1), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
    }

    @Test fun failedRegistrationRollsBackEntireSelectionAndCancelsStagedIntent() = runBlocking {
        val date = monday(7)
        val initial = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        initial.save(daily("a"), PrivateAlarm())
        initial.save(daily("b", 10), PrivateAlarm())
        val old = runs().toSet()
        var registrations = 0
        val failing = AlarmEngine(app, clock = { clock }, zoneId = { testZone }, registerReplacement = {
            app.scheduler.schedule(it)
            if (++registrations == 2) error("simulated scheduler exception")
        })
        assertTrue(runCatching { failing.skipTomorrow(setOf("a", "b"), false) }.isFailure)
        assertEquals(2, registrations)
        assertEquals(old, runs().toSet())
        assertTrue(app.runtime.dao().alarms().all { it.model().skipEpochDays.isEmpty() })
        val system = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(2, system.size)
        assertEquals(instant(date.plusDays(1), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
    }

    @Test fun editAndTimeZoneChangeKeepLocalSkipWithoutDuplicateOfTodaysRun() = runBlocking {
        val day = monday(5)
        var zone = testZone
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { zone })
        coordinator.save(daily("a"), PrivateAlarm())
        coordinator.skipTomorrow(setOf("a"), false)
        val current = runs().single()
        clock = current.eventAt
        coordinator.event(current.id, current.version)
        val alarm = app.runtime.dao().alarms().single().model()
        zone = ZoneId.of("Europe/Berlin")
        clock = instant(day, 5, zone = zone)
        coordinator.save(alarm.copy(hour = 7), PrivateAlarm())
        coordinator.restore(recalculate = true)
        assertEquals(instant(day.plusDays(2), 7, zone = zone),
            runs().single { it.state == RunState.SCHEDULED }.eventAt)
        assertEquals(1, runs().count { it.sounding })
    }

    @Test fun ringingLimitUsesMonotonicTimeAcrossClockChangeAndRestore() = runBlocking {
        val day = monday(5)
        var elapsedNow = 100_000L
        val coordinator = AlarmEngine(app, clock = { clock }, elapsed = { elapsedNow }, zoneId = { testZone })
        coordinator.save(daily("a"), PrivateAlarm())
        val current = runs().single()
        clock = current.eventAt
        coordinator.event(current.id, current.version)
        clock += 2 * WakeMachine.RING_LIMIT_MILLIS
        coordinator.restore(recalculate = true)
        assertEquals(RunState.RINGING, runs().first { it.id == current.id }.state)
        elapsedNow += WakeMachine.RING_LIMIT_MILLIS
        coordinator.expireRings()
        assertEquals(RunState.MISSED, runs().first { it.id == current.id }.state)
        assertEquals(1, runs().count { it.state == RunState.SCHEDULED })
    }

    @Test fun defaultSessionRetiresOldMissionsAndOnlyLatestCanScheduleCheck() = runBlocking {
        val day = monday(5)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        repeat(3) { i -> coordinator.save(daily("wake$i", i * 10).copy(missions = listOf(MissionSpec(count = 1))), PrivateAlarm()) }
        val scheduled = runs().sortedBy { it.scheduledAt }
        scheduled.forEach { run ->
            clock = run.eventAt
            coordinator.event(run.id, run.version)
            coordinator.update(run.id, WakeMachine::startMission)
        }

        val current = scheduled.last()
        coordinator.event(scheduled.first().id, scheduled.first().version)
        scheduled.dropLast(1).forEach { old ->
            coordinator.update(old.id) { run, now -> WakeMachine.progress(run, run.version, now) }
            assertEquals(RunState.MISSED, runs().first { it.id == old.id }.state)
        }
        coordinator.update(current.id) { run, now -> WakeMachine.progress(run, run.version, now) }
        assertEquals(listOf(current.id), runs().filter { it.state == RunState.WAITING_WAKE_CHECK }.map { it.id })
        assertEquals(3, runs().count { it.state == RunState.SCHEDULED })
    }

    @Test fun lateEarlierSignalDoesNotReplaceTheNewerMission() = runBlocking {
        val day = monday(5)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        coordinator.save(daily("early"), PrivateAlarm())
        coordinator.save(daily("later", 10), PrivateAlarm())
        val (early, later) = runs().sortedBy { it.scheduledAt }
        clock = instant(day, 6, 10)
        coordinator.event(later.id, later.version)
        coordinator.event(early.id, early.version)
        assertEquals(listOf(later.id), runs().filter { it.sounding }.map { it.id })
        assertEquals(RunState.MISSED, runs().first { it.id == early.id }.state)
    }

    @Test fun laterRingsPreemptAndEndingQueueLeavesFutureAlarmsAndWakeChecks() = runBlocking {
        val day = monday(5)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        for (i in 0..3) coordinator.save(daily("a$i", i * 10).copy(independent = true), PrivateAlarm())
        for (i in 0..3) {
            val next = runs().single { it.plan.id == "a$i" && it.state == RunState.SCHEDULED }
            clock = instant(day, 6, i * 10)
            coordinator.event(next.id, next.version)
            coordinator.event(next.id, next.version)
            assertEquals(next.id, RingingPriority.next(runs())!!.id)
        }
        val active = RingingPriority.ordered(runs())
        assertEquals(4, active.size)
        coordinator.update(active.last().id, WakeMachine::startMission)
        coordinator.update(active.last().id) { run, time -> WakeMachine.progress(run, run.version, time) }
        assertEquals(3, coordinator.endCurrentRings(active.map { it.id }.toSet()))
        assertEquals(4, runs().count { it.state == RunState.SCHEDULED })
        assertEquals(1, runs().count { it.state == RunState.WAITING_WAKE_CHECK })
        assertEquals(3, runs().count { it.state == RunState.CANCELLED && it.firedAt > 0 })
    }

    @Test fun secondSkipDoesNotBringBackPreviouslySkippedToday() = runBlocking {
        val date = monday(7)
        val coordinator = AlarmEngine(app, clock = { clock }, zoneId = { testZone })
        coordinator.save(daily("a"), PrivateAlarm())
        coordinator.skipTomorrow(setOf("a"), false)
        clock = instant(date.plusDays(1), 5)
        coordinator.skipTomorrow(setOf("a"), false)
        assertEquals(instant(date.plusDays(3), 6), app.scheduler.nextSystemAlarm()!!.triggerTime)
        assertEquals(2, app.runtime.dao().alarms().single().model().skipEpochDays.size)
    }

}

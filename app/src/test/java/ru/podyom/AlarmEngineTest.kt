package ru.podyom

import android.app.AlarmManager
import android.os.UserManager
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
        app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).setCanScheduleExactAlarms(true)
        engine = AlarmEngine(app) { clock }
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
        AlarmEngine(app) {clock}.restore()
        assertEquals(saved.eventAt,app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        clock=saved.eventAt;engine.event(saved.id,saved.version)
        engine.update(r.id) {a,t->WakeMachine.confirm(a,t,false)}
        assertEquals(RunState.COMPLETED,runs().single().state)
        assertNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
    }
    @Test fun parallelAlarmsRemainIndependent() = runBlocking {
        engine.save(plan("a"),PrivateAlarm(),clock+1000);engine.save(plan("b"),PrivateAlarm(),clock+1000)
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
        Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).setCanScheduleExactAlarms(false)
        try {engine.save(plan(),PrivateAlarm());fail("Must reject enabled alarm")} catch (_:IllegalStateException) { }
        assertTrue(runs().isEmpty())
    }
}

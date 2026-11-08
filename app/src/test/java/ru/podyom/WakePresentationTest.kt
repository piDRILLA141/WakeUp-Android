package ru.podyom

import android.app.NotificationManager
import android.app.AlarmManager
import android.os.UserManager
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import ru.podyom.alarm.*
import ru.podyom.core.*
import ru.podyom.data.*
import ru.podyom.wakecheck.WakeCheckOverlay

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=App::class)
class WakePresentationTest {
    private lateinit var app:App
    private lateinit var engine:AlarmEngine
    private var clock=System.currentTimeMillis()
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication() as App
        Shadows.shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        engine=AlarmEngine(app,clock={clock})
    }
    @After fun close(){app.runtime.close();app.privateDb.close()}
    private suspend fun active():AlarmRun {
        engine.save(AlarmPlan("a",missions=listOf(MissionSpec(count=1))),PrivateAlarm(),clock+1000)
        var r=app.runtime.dao().runs().single().model();clock+=1000;engine.event(r.id,0)
        engine.update(r.id,WakeMachine::startMission);engine.update(r.id){run,t->WakeMachine.progress(run,run.version,t)}
        r=app.runtime.dao().run(r.id)!!.model();clock=r.eventAt;engine.event(r.id,r.version)
        return app.runtime.dao().run(r.id)!!.model()
    }
    @Test fun activeCheckHasSeparateAudibleChannelActionAndCountdownThenCleansUp()=runBlocking {
        val run=active();val manager=app.getSystemService(NotificationManager::class.java)
        val shadow=Shadows.shadowOf(manager)
        assertNull(shadow.getNotification(run.id,42))
        val notification=shadow.getNotification(run.id,43)!!
        assertEquals(AlarmNotifications.CHECK,notification.channelId)
        assertEquals(android.app.Notification.CATEGORY_ALARM,notification.category)
        assertNotNull(notification.contentIntent);assertEquals(2,notification.actions.size)
        val channel=manager.getNotificationChannel(AlarmNotifications.CHECK)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance);assertNotNull(channel.sound);assertTrue(channel.shouldVibrate())
        engine.confirmCheck(run.id,expectedVersion=run.version)
        assertNull(shadow.getNotification(run.id,43));assertNull(shadow.getNotification(run.id,42))
        assertNull(app.scheduler.nextSystemAlarm())
        engine.confirmCheck(run.id,expectedVersion=run.version)
        assertEquals(RunState.COMPLETED,app.runtime.dao().run(run.id)!!.model().state)
    }
    @Test fun missedCheckCreatesFreshNotificationOnNextCycle()=runBlocking {
        var run=active();val notifications=Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        clock=run.deadline;engine.event(run.id,run.version)
        assertNull(notifications.getNotification(run.id,43))
        assertEquals(RunState.RE_RINGING,app.runtime.dao().run(run.id)!!.model().state)
        engine.update(run.id,WakeMachine::startMission);engine.update(run.id){r,t->WakeMachine.progress(r,r.version,t)}
        run=app.runtime.dao().run(run.id)!!.model();clock=run.eventAt;engine.event(run.id,run.version)
        val active=app.runtime.dao().run(run.id)!!.model()
        val notification=notifications.getNotification(run.id,43)!!
        assertEquals(active.deadline,notification.`when`)
        assertTrue(runCatching {engine.confirmCheck(run.id,expectedVersion=run.version)}.isFailure)
    }
    @Test fun overlayOnlyAppearsWithOptInPermissionAndBackgroundAndIsRemoved()=runBlocking {
        val run=active()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val overlay=WakeCheckOverlay(app,scope)
        try {
            overlay.refresh(run,false);assertFalse(overlay.showing)
            app.settings.enhanced(true)
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
            Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
            overlay.refresh(run,false);assertTrue(overlay.showing)
            overlay.refresh(run,false);assertTrue(overlay.showing)
            overlay.refresh(run,true);assertFalse(overlay.showing)
            overlay.refresh(run,false);assertTrue(overlay.showing)
            engine.confirmCheck(run.id,expectedVersion=run.version)
            overlay.refresh(null,false);assertFalse(overlay.showing)
        } finally {overlay.close();scope.cancel()}
    }
    @Test fun confirmationAtDeadlineNeverCompletes()=runBlocking {
        val run=active();clock=run.deadline
        assertTrue(runCatching {engine.confirmCheck(run.id,expectedVersion=run.version)}.isFailure)
        engine.event(run.id,run.version)
        assertEquals(RunState.RE_RINGING,app.runtime.dao().run(run.id)!!.model().state)
    }
    @Test fun upgradeDoesNotUnmuteDisabledLegacyChannel() {
        val old=android.app.NotificationChannel("wake_check_v1","Старая проверка",NotificationManager.IMPORTANCE_NONE)
        val migrated=AlarmNotifications.checkChannel(old)
        assertEquals(NotificationManager.IMPORTANCE_NONE,migrated.importance)
    }

}

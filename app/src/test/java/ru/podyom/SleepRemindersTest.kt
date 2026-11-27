package ru.podyom

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.podyom.alarm.SleepReminders
import ru.podyom.core.AlarmPlan
import ru.podyom.core.AlarmRun
import ru.podyom.core.SleepSettings
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class SleepRemindersTest {
    @Test fun reconfigurationAndRestoreDoNotChangeExactAlarmAndOnlyCurrentReminderDelivers() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        var zone = ZoneId.of("UTC")
        var time = Instant.parse("2026-11-27T19:00:00Z").toEpochMilli()
        fun reminder() = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == SleepReminders.ACTION }
        val coordinator = SleepReminders(app, { time }, { zone })
        val precise = AlarmRun("fixture", AlarmPlan("fixture"), maxOf(System.currentTimeMillis(), time) + 60_000)
        app.scheduler.schedule(precise)
        coordinator.configure(SleepSettings(enabled = true, hour = 23, minute = 0, leadMinutes = 30))
        val old = shadowOf(reminder().operation).savedIntent
        val oldRevision = old.getLongExtra("revision", -1)
        val oldTime = old.getLongExtra("scheduledAt", -1)
        coordinator.configure(SleepSettings(enabled = true, hour = 22, minute = 0, leadMinutes = 15))
        val fresh = SleepReminders(app, { time }, { zone })
        zone = ZoneId.of("Europe/Moscow")
        fresh.restore()
        val current = shadowOf(reminder().operation).savedIntent
        val revision = current.getLongExtra("revision", -1)
        val at = current.getLongExtra("scheduledAt", -1)
        assertEquals(Instant.parse("2026-11-28T18:45:00Z").toEpochMilli(), at)
        time = at + 1_000
        fresh.deliver(oldRevision, oldTime)
        assertNull(notifications.getNotification(SleepReminders.REMINDER_ID))
        fresh.deliver(revision, at)
        val notification = requireNotNull(notifications.getNotification(SleepReminders.REMINDER_ID))
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertNull(notification.fullScreenIntent)
        assertEquals(SleepReminders.CHANNEL, notification.channelId)
        assertEquals(at, app.settings.sleepRecord().notifiedAt)
        fresh.deliver(revision, at)
        assertTrue(shadowOf(reminder().operation).savedIntent.getLongExtra("scheduledAt", -1) > at)
        fresh.configure(SleepSettings())
        assertTrue(alarms.scheduledAlarms.none { shadowOf(it.operation).savedIntent.action == SleepReminders.ACTION })
        assertEquals(precise.eventAt, app.scheduler.nextSystemAlarm()?.triggerTime)
    }
}

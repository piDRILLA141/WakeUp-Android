package ru.podyom.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.App
import ru.podyom.R
import ru.podyom.app
import ru.podyom.core.SleepSchedule
import ru.podyom.core.SleepSettings
import ru.podyom.data.SleepRecord
import ru.podyom.data.TechnicalEvent
import ru.podyom.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/** Optional inexact RTC notification, separate from the precise alarm engine. */
class SleepReminders(
    private val app: App,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    suspend fun configure(settings: SleepSettings) = lock.withLock {
        val record = app.settings.changeSleep(settings)
        schedule(record)
        if (!settings.enabled) notifications.cancel(REMINDER_ID)
    }

    suspend fun restore() = lock.withLock {
        if (app.unlocked) schedule(app.settings.sleepRecord())
    }

    private fun event() = Intent(app, SleepReminderReceiver::class.java).setAction(ACTION)

    private fun schedule(record: SleepRecord) {
        val next = SleepSchedule.next(record.settings, Instant.ofEpochMilli(clock()), zone())
        if (next == null) {
            PendingIntent.getBroadcast(app, 0, event(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            return
        }
        val pending = PendingIntent.getBroadcast(app, 0, event()
            .putExtra("revision", record.revision).putExtra("scheduledAt", next.notifyAt.toEpochMilli()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.set(AlarmManager.RTC_WAKEUP, next.notifyAt.toEpochMilli(), pending)
        app.journal.report(TechnicalEvent.SLEEP_REMINDER_SCHEDULED)
    }

    suspend fun deliver(revision: Long, scheduledAt: Long) = lock.withLock {
        if (!app.unlocked || scheduledAt <= 0) return@withLock
        val record = app.settings.sleepRecord()
        if (!record.settings.enabled || record.revision != revision || record.notifiedAt == scheduledAt) return@withLock
        val now = clock()
        if (scheduledAt > now) return@withLock
        val occurrence = SleepSchedule.next(record.settings, Instant.ofEpochMilli(scheduledAt - 1), zone())
        schedule(record)
        if (occurrence == null || occurrence.notifyAt.toEpochMilli() != scheduledAt || occurrence.bedAt.toEpochMilli() <= now) return@withLock
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return@withLock
        app.settings.markSleepNotified(revision, scheduledAt)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания о сне", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, REMINDER_ID,
            Intent(app, MainActivity::class.java).setAction(OPEN_SLEEP).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(REMINDER_ID, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Скоро время сна")
            .setContentText("Откройте режим сна, чтобы увидеть расписание.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true).build())
        app.journal.report(TechnicalEvent.SLEEP_REMINDER_SHOWN)
    }

    companion object {
        const val ACTION = "ru.podyom.SLEEP_REMINDER"
        const val OPEN_SLEEP = "ru.podyom.OPEN_SLEEP"
        const val CHANNEL = "sleep_reminder_v1"
        const val REMINDER_ID = 317
    }
}

class SleepReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepReminders.ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.app.sleepReminders.deliver(intent.getLongExtra("revision", -1), intent.getLongExtra("scheduledAt", -1))
            } catch (_: Exception) { context.app.journal.report(TechnicalEvent.SLEEP_REMINDER_ERROR) }
            finally { pending.finish() }
        }
    }
}

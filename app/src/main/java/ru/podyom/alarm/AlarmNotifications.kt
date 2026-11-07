package ru.podyom.alarm

import androidx.core.net.toUri
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.podyom.R
import ru.podyom.core.*
import ru.podyom.ringing.RingingActivity
import ru.podyom.ui.MainActivity

object AlarmNotifications {
    const val RING = "alarm_v1"
    const val CHECK = "wake_check_v1"
    const val STATUS = "status_v1"
    const val SERVICE_ID = 41
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(RING, "Звонок будильника", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Звук воспроизводит служба будильника"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(CHECK, "Проверка пробуждения", NotificationManager.IMPORTANCE_HIGH).apply { description = "Подтверждение и повторный звонок" },
            NotificationChannel(STATUS, "Ожидание и диагностика", NotificationManager.IMPORTANCE_LOW)
        ))
    }
    fun activity(context: Context, id: String) = PendingIntent.getActivity(context, 0,
        Intent(context, RingingActivity::class.java).setData("podyom://screen/$id".toUri())
            .putExtra("runId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null)
    fun ringing(context: Context, run: AlarmRun? = null, title: String = "Подъём") =
        NotificationCompat.Builder(context, RING).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title).setContentText(if (run == null) "Подготовка звонка…" else "Пора вставать · нажмите, чтобы выполнить задание")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
            .apply { if (run != null) { val open = activity(context, run.id); setContentIntent(open); setFullScreenIntent(open, true) } }.build()
    fun refreshChecks(context: Context, runs: List<AlarmRun>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runs.forEach { r ->
            val active = r.state == RunState.WAKE_CHECK_ACTIVE
            if (r.state !in setOf(RunState.WAITING_WAKE_CHECK, RunState.WAKE_CHECK_ACTIVE, RunState.SNOOZED)) {
                manager.cancel(r.id, 42); return@forEach
            }
            val builder = NotificationCompat.Builder(context, if (active) CHECK else STATUS)
                .setSmallIcon(R.drawable.ic_alarm).setOngoing(true).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentTitle(if (active) "Ты точно проснулся?" else if (r.state == RunState.SNOOZED) "Будильник отложен" else "Проверка пробуждения запланирована")
                .setContentText(if (active) "Подтверди, что ты не уснул снова" else "Следующее событие в ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date(r.eventAt))}")
                .setContentIntent(activity(context, r.id)).setOnlyAlertOnce(true)
            if (active) builder.setFullScreenIntent(activity(context, r.id), true).setWhen(r.deadline).setUsesChronometer(true).setChronometerCountDown(true)
            try { manager.notify(r.id, 42, builder.build()) } catch (_: SecurityException) { }
        }
    }
    fun failure(context: Context, message: String) {
        val open = PendingIntent.getActivity(context, 99, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { context.getSystemService(NotificationManager::class.java).notify(99,
            NotificationCompat.Builder(context, CHECK).setSmallIcon(R.drawable.ic_alarm).setContentTitle("Проверьте будильник")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(open).build()) }
        catch (_: SecurityException) { }
    }
}

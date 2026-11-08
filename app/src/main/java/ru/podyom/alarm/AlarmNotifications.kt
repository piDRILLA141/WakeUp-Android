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
    const val CHECK = "wake_check_v2"
    const val ENHANCED_CHECK = "wake_check_enhanced_v2"
    const val STATUS = "status_v1"
    const val SERVICE_ID = 41
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(RING, "Звонок будильника", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Звук воспроизводит служба будильника"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            checkChannel(manager.getNotificationChannel("wake_check_v1")),
            NotificationChannel(ENHANCED_CHECK, "Усиленная проверка пробуждения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Короткий сигнал и вибрация; наложение включается отдельно пользователем"
                setSound("android.resource://${context.packageName}/${R.raw.dawn}".toUri(),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                enableVibration(true);vibrationPattern=longArrayOf(0,300,200,300,200,300);lockscreenVisibility=Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(STATUS, "Ожидание и диагностика", NotificationManager.IMPORTANCE_LOW)
        ))
    }
    internal fun checkChannel(previous:NotificationChannel?):NotificationChannel {
        val importance=if(previous!=null && (previous.importance==NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT>=29 && previous.hasUserSetImportance()))) previous.importance else NotificationManager.IMPORTANCE_HIGH
        return NotificationChannel(CHECK,"Активная проверка пробуждения",importance).apply {
            description="Новый канал 0.2: подтверждение перед повторным звонком; прежние каналы сохранены"
            val sound=if(previous!=null && Build.VERSION.SDK_INT>=30 && previous.hasUserSetSound()) previous.sound
                else android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            setSound(sound,android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            val customized=previous!=null && (previous.importance==NotificationManager.IMPORTANCE_NONE ||
                (Build.VERSION.SDK_INT>=29 && previous.hasUserSetImportance()) || (Build.VERSION.SDK_INT>=30 && previous.hasUserSetSound()))
            if(customized && previous!=null) {
                vibrationPattern=previous.vibrationPattern;enableVibration(previous.shouldVibrate())
            } else {enableVibration(true);vibrationPattern=longArrayOf(0,200,150,200)}
            lockscreenVisibility=Notification.VISIBILITY_PUBLIC
        }
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
                manager.cancel(r.id, 42); manager.cancel(r.id,43); return@forEach
            }
            manager.cancel(r.id,if(active) 42 else 43)
            val channel=if(ru.podyom.data.enhancedChecks(context)) ENHANCED_CHECK else CHECK
            val builder = NotificationCompat.Builder(context, if (active) channel else STATUS)
                .setSmallIcon(R.drawable.ic_alarm).setOngoing(true).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentTitle(if (active) "Ты точно проснулся?" else if (r.state == RunState.SNOOZED) "Будильник отложен" else "Проверка пробуждения запланирована")
                .setContentText(if (active) "Подтверди пробуждение, чтобы будильник не повторился" else "Следующее событие в ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date(r.eventAt))}")
                .setContentIntent(activity(context, r.id)).setOnlyAlertOnce(true)
            if(active) {
                builder.setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Подтверди пробуждение, чтобы будильник не повторился. Если не подтвердить, сигнал повторится."))
                    .setWhen(r.deadline).setUsesChronometer(true).setChronometerCountDown(true)
                if(Build.VERSION.SDK_INT<34 || manager.canUseFullScreenIntent()) builder.setFullScreenIntent(activity(context,r.id),true)
                val confirm=if(r.plan.strictCheck) activity(context,r.id) else PendingIntent.getBroadcast(context,0,
                    Intent(context,AlarmReceiver::class.java).setAction("ru.podyom.CONFIRM_WAKE")
                        .setData("podyom://confirm/${r.id}/${r.version}".toUri()).putExtra("runId",r.id).putExtra("version",r.version),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(0,if(r.plan.strictCheck) "Выполнить проверку" else "ДА, Я ПРОСНУЛСЯ",confirm)
                builder.addAction(0,"Открыть",activity(context,r.id))
            }
            try { manager.notify(r.id, if(active) 43 else 42, builder.build()) } catch (_: SecurityException) { }
        }
    }
    fun checkForeground(context:Context,run:AlarmRun) = NotificationCompat.Builder(context,STATUS)
        .setSmallIcon(R.drawable.ic_alarm).setContentTitle("Проверка пробуждения активна")
        .setContentText("Осталось подтвердить пробуждение").setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(activity(context,run.id)).build()
    fun failure(context: Context, message: String) {
        val open = PendingIntent.getActivity(context, 99, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { context.getSystemService(NotificationManager::class.java).notify(99,
            NotificationCompat.Builder(context, CHECK).setSmallIcon(R.drawable.ic_alarm).setContentTitle("Проверьте будильник")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(open).build()) }
        catch (_: SecurityException) { }
    }
}

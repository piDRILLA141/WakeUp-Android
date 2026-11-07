package ru.podyom.alarm

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import ru.podyom.core.AlarmRun
import ru.podyom.ui.MainActivity

class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun allowed() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun pending(run: AlarmRun) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction("ru.podyom.EVENT")
            .setData(Uri.parse("podyom://run/${run.id}/${run.version}"))
            .putExtra("runId", run.id).putExtra("version", run.version), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(run: AlarmRun, at: Long = run.eventAt) {
        check(allowed()) { "Нет разрешения на точные будильники" }
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at.coerceAtLeast(System.currentTimeMillis() + 100), show), pending(run))
    }
    fun cancel(run: AlarmRun) { val pi = pending(run); manager.cancel(pi); pi.cancel() }
}

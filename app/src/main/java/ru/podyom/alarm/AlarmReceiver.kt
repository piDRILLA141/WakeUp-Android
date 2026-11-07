package ru.podyom.alarm

import android.content.*
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import ru.podyom.app
import ru.podyom.ringing.RingingService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "ru.podyom.EVENT") return
        val id = intent.getStringExtra("runId") ?: return
        try {
            ContextCompat.startForegroundService(context, Intent(context, RingingService::class.java)
                .putExtra("runId", id).putExtra("version", intent.getIntExtra("version", -1)))
        } catch (e: RuntimeException) {
            AlarmNotifications.failure(context, "Android запретил запуск звонка. Откройте приложение и проверьте разрешения.")
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podyom:restore")
        lock.acquire(20_000)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { context.app.engine.restore(intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) }
            catch (e: Exception) { AlarmNotifications.failure(context, "Не удалось восстановить расписание: ${e.javaClass.simpleName}") }
            finally { if (lock.isHeld) lock.release(); result.finish() }
        }
    }
}

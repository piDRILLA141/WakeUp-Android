package ru.podyom.alarm

import android.content.*
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import ru.podyom.app
import ru.podyom.core.CheckKey
import ru.podyom.core.CheckConfirmation
import ru.podyom.data.TechnicalEvent
import android.widget.Toast
import ru.podyom.ringing.RingingService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("ru.podyom.EVENT","ru.podyom.CONFIRM_WAKE")) return
        val id = intent.getStringExtra("runId") ?: return
        if (intent.action == "ru.podyom.CONFIRM_WAKE") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val cycle = intent.getIntExtra("checkCycle", -1)
                    val index = intent.getIntExtra("checkIndex", -1)
                    val engine = context.app.engine
                    if (cycle >= 0 && index >= 0) {
                        val result = engine.confirmWake(CheckKey(id, cycle, index))
                        if (result in setOf(CheckConfirmation.ACCEPTED, CheckConfirmation.ALREADY_CONFIRMED)) {
                            Toast.makeText(context, "Пробуждение подтверждено", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        engine.confirmCheck(id, expectedVersion = intent.getIntExtra("version", -1))
                    }
                } catch (_: Exception) {
                    context.app.journal.report(TechnicalEvent.LAUNCH_ERROR, id, code = "notification")
                } finally { pending.finish() }
            }
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, RingingService::class.java)
                .putExtra("runId", id).putExtra("version", intent.getIntExtra("version", -1)))
        } catch (e: RuntimeException) {
            context.app.journal.report(TechnicalEvent.LAUNCH_ERROR, id, intent.getIntExtra("version", -1), "foreground_service")
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

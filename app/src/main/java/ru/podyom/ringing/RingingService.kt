package ru.podyom.ringing

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.app
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.data.model

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audio: AlarmAudio
    private var activeId: String? = null
    private var observer: Job? = null
    private val commands = Mutex()
    private var inFlight = 0
    private var lock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
        audio = AlarmAudio(this, scope)
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podyom:dispatch").apply { acquire(30_000) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        inFlight++
        scope.launch {
            commands.withLock {
                try {
                    val id = intent?.getStringExtra("runId")
                    if (id != null) app.engine.event(id, intent.getIntExtra("version", -1))
                    else app.engine.restore()
                } finally { inFlight-- }
                if (observer == null) observe()
                refresh()
            }
        }
        return START_STICKY
    }
    private fun observe() {
        observer = scope.launch { app.runtime.dao().observeRuns().collectLatest { refresh() } }
    }
    private suspend fun refresh() {
        // Earliest ringing occurrence wins. Other occurrences remain durable and become audible in turn.
        val selected = app.engine.snapshot().filter { it.sounding }.minByOrNull { it.scheduledAt }
        if (selected == null) {
            if (inFlight > 0) return
            audio.stop(); activeId = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (selected.id != activeId) {
            activeId = selected.id
            audio.start(selected, app.engine.profile(selected.plan.privateKey))
        } else audio.duck(selected.plan.duckDuringMission && selected.state == ru.podyom.core.RunState.MISSION_RUNNING)
        val label = app.engine.profile(selected.plan.privateKey)?.title ?: "Подъём · разблокируйте телефон"
        getSystemService(android.app.NotificationManager::class.java).notify(AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(this, selected, label))
    }
    override fun onDestroy() { audio.stop(); scope.cancel(); lock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

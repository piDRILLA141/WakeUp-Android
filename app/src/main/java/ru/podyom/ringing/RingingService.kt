package ru.podyom.ringing

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import ru.podyom.core.RunState
import ru.podyom.wakecheck.WakeCheckOverlay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.app
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.data.model

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audio: AlarmAudio
    private lateinit var overlay:WakeCheckOverlay
    private var activeId: String? = null
    private var observer: Job? = null
    private val commands = Mutex()
    private var inFlight = 0
    private var lock: PowerManager.WakeLock? = null
    private val screenReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {scope.launch {refresh()}}
    }
    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
        audio = AlarmAudio(this, scope)
        overlay=WakeCheckOverlay(this,scope)
        androidx.core.content.ContextCompat.registerReceiver(this,screenReceiver,
            IntentFilter().apply {addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_SCREEN_OFF)},
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podyom:dispatch").apply { acquire(30_000) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        inFlight++
        scope.launch {
            commands.withLock {
                try {
                    val id = intent?.getStringExtra("runId")
                    if (id != null && intent.getBooleanExtra("confirmWake",false)) app.engine.confirmCheck(id,expectedVersion=intent.getIntExtra("version",-1))
                    else if (id != null) app.engine.event(id, intent.getIntExtra("version", -1))
                    else app.engine.restore()
                } catch(e:Exception) {
                    if(e is CancellationException) { throw e }
                    AlarmNotifications.failure(this@RingingService,e.message?:"Ошибка обработки события")
                } finally { inFlight-- }
                if (observer == null) observe()
                refresh()
            }
        }
        return START_STICKY
    }
    private fun observe() {
        observer = scope.launch { combine(app.runtime.dao().observeRuns(),app.foreground) {rows,foreground->rows to foreground}.collectLatest { refresh() } }
    }
    private suspend fun refresh() {
        // Earliest ringing occurrence wins. Other occurrences remain durable and become audible in turn.
        val runs=app.engine.snapshot()
        val selected = runs.filter { it.sounding }.minByOrNull { it.scheduledAt }
        val check=runs.filter {it.state==RunState.WAKE_CHECK_ACTIVE}.minByOrNull {it.deadline}
        overlay.refresh(check,app.foreground.value)
        if (selected == null) {
            if (inFlight > 0) return
            audio.stop(); activeId = null
            if(check!=null) {
                getSystemService(android.app.NotificationManager::class.java).notify(AlarmNotifications.SERVICE_ID,AlarmNotifications.checkForeground(this,check))
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (selected.id != activeId) {
            activeId = selected.id
            audio.start(selected, app.engine.profile(selected.plan.privateKey))
        } else audio.duck(selected.plan.duckDuringMission && selected.state == ru.podyom.core.RunState.MISSION_RUNNING)
        val label = app.engine.profile(selected.plan.privateKey)?.title ?: "Подъём · разблокируйте телефон"
        getSystemService(android.app.NotificationManager::class.java).notify(AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(this, selected, label))
    }
    override fun onDestroy() { unregisterReceiver(screenReceiver); overlay.close(); audio.stop(); scope.cancel(); lock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

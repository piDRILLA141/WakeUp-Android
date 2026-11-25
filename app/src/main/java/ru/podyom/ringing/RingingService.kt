package ru.podyom.ringing

import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.app
import ru.podyom.core.*
import ru.podyom.data.TechnicalEvent
import ru.podyom.wakecheck.WakeCheckOverlay

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audio: AlarmAudio
    private lateinit var overlay: WakeCheckOverlay
    private var activeId: String? = null
    private var publishedCheck: Pair<CheckKey, Int>? = null
    private var expiration: Job? = null
    private var expiresAt: Long? = null
    private var audioVersion = 0
    private var observer: Job? = null
    private val commands = Mutex()
    private var inFlight = 0
    private var lock: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { commands.withLock { refresh() } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
        audio = AlarmAudio(this, scope) { id, signal ->
            app.engine.recordAudio(id, audioVersion, signal)
            if (id == activeId) {
                signal.detail?.let { AlarmNotifications.audioFailure(this, id, it) }
                if (signal == AudioSignal.PLAYBACK_STARTED &&
                    getSystemService(android.media.AudioManager::class.java).getStreamVolume(android.media.AudioManager.STREAM_ALARM) > 0) {
                    AlarmNotifications.clearAudioFailure(this)
                }
            }
        }
        overlay = WakeCheckOverlay(this, scope)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podyom:dispatch").apply {
            acquire(30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        inFlight++
        scope.launch {
            commands.withLock {
                try {
                    val id = intent?.getStringExtra("runId")
                    when {
                        id == null -> app.engine.restore(recoverSound = false)
                        intent?.getBooleanExtra("confirmWake", false) == true ->
                            app.engine.confirmCheck(id, expectedVersion = intent.getIntExtra("version", -1))
                        else -> app.engine.event(id, intent?.getIntExtra("version", -1) ?: -1)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AlarmNotifications.failure(this@RingingService, e.message ?: "Ошибка обработки события")
                } finally {
                    inFlight--
                }
                if (observer == null) observe()
                refresh()
            }
        }
        return START_STICKY
    }

    private fun observe() {
        observer = scope.launch {
            combine(app.runtime.dao().observeLiveRuns(), app.foreground) { _, _ -> Unit }
                .collect { commands.withLock { refresh() } }
        }
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        var runs = app.engine.snapshot()
        if (runs.any { it.sounding && now >= WakeMachine.ringExpiresAt(it) }) {
            app.engine.expireRings()
            runs = app.engine.snapshot()
        }
        val selected = RingingPriority.next(runs)
        val check = nextActiveCheck(runs, now)
        watchExpiration(runs)
        overlay.refresh(check, app.foreground.value)

        if (selected == null) {
            if (activeId != null) {
                audio.stop()
                activeId = null
                AlarmNotifications.clearAudioFailure(this)
            }
            if (inFlight > 0) return
            if (check != null) {
                if (publishedCheck != (check.checkKey to check.version)) {
                    publishedCheck = check.checkKey to check.version
                    notifications.notify(AlarmNotifications.SERVICE_ID, AlarmNotifications.checkForeground(this, check))
                }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        publishedCheck = null
        audioVersion = selected.version
        if (activeId == selected.id) {
            audio.update(selected)
            return
        }

        // Release the old player before resolving the new profile. Its durable run remains in the queue.
        audio.stop()
        AlarmNotifications.clearAudioFailure(this)
        activeId = selected.id
        val profile = runCatching { app.engine.profile(selected.plan.privateKey) }.getOrNull()
        app.journal.report(TechnicalEvent.RING_PRIORITY_CHANGED, selected.id, selected.version)
        audio.start(selected, profile)
        notifications.notify(AlarmNotifications.SERVICE_ID, AlarmNotifications.ringing(
            this, selected, profile?.title ?: "Подъём · разблокируйте телефон", announce = true,
        ))
    }

    private fun watchExpiration(runs: List<AlarmRun>) {
        val next = runs.filter { it.sounding }.minOfOrNull(WakeMachine::ringExpiresAt)
        if (next == expiresAt) return
        expiresAt = next
        expiration?.cancel()
        expiration = next?.let { deadline ->
            scope.launch {
                delay((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
                commands.withLock {
                    expiration = null
                    expiresAt = null
                    refresh()
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        overlay.close()
        audio.stop()
        AlarmNotifications.clearAudioFailure(this)
        scope.cancel()
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

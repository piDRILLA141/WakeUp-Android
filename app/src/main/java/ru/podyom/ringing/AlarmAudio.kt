package ru.podyom.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.net.toUri
import kotlinx.coroutines.*
import ru.podyom.R
import ru.podyom.core.AlarmRun
import ru.podyom.core.AudioRecovery
import ru.podyom.core.PrivateAlarm
import ru.podyom.core.RunState

/** All player and focus operations are confined to the service's main thread. */
class AlarmAudio(
    private val context: Context,
    private val scope: CoroutineScope,
    private val report: (String, AudioSignal) -> Unit,
) {
    private enum class Focus { NONE, DELAYED, HELD, TRANSIENT, LOST }
    private val manager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val preparationWakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podyom:audio-recovery")
        .apply { setReferenceCounted(false) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val fallback = "android.resource://${context.packageName}/${R.raw.dawn}".toUri()
    private var owner: AlarmRun? = null
    private var source: Uri? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private var preparedBy = 0L
    private var startedAt: Long? = null
    private var focused = Focus.NONE
    private var request: AudioFocusRequest? = null
    private var focusRequests = 0
    private var focusRetryAt = 0L
    private var suspendedForCall = false
    private var vibrating = false
    private var recovery = AudioRecovery()
    private var retryAt = 0L
    private var monitor: Job? = null
    private var routeRegistered = false

    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
            if (routeRegistered) signal(AudioSignal.ROUTE_CHANGED)
        }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (routeRegistered) signal(AudioSignal.ROUTE_CHANGED)
        }
    }

    fun start(run: AlarmRun, profile: PrivateAlarm?) {
        stop()
        owner = run
        source = profile?.soundUri?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        recovery = AudioRecovery()
        keepAwakeForPreparation()
        try {
            manager.registerAudioDeviceCallback(routes, handler)
            routeRegistered = true
        } catch (_: RuntimeException) {
            signal(AudioSignal.ROUTE_MONITOR_UNAVAILABLE)
        }
        if (manager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) signal(AudioSignal.VOLUME_ZERO)
        monitor = scope.launch {
            while (isActive) {
                tick()
                delay(if (owner?.plan?.rampSeconds != 0 && startedAt?.let {
                    SystemClock.elapsedRealtime() - it < (owner?.plan?.rampSeconds ?: 0) * 1000L
                } == true) 200L else 1_000L)
            }
        }
    }

    fun update(run: AlarmRun) {
        if (owner?.id == run.id) {
            owner = run
            applyVolume()
        }
    }

    private fun tick() {
        val run = owner ?: return
        val now = SystemClock.elapsedRealtime()
        val inCall = when (manager.mode) {
            AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
        if (inCall) {
            if (!suspendedForCall) {
                signal(AudioSignal.CALL_PAUSED)
                suspendedForCall = true
                releasePlayer()
                releasePreparationWakeLock()
                abandonFocus()
                stopVibration()
            }
            return
        }
        if (suspendedForCall) {
            suspendedForCall = false
            focusRequests = 0
            focusRetryAt = now
        }
        if (run.plan.vibrate && !vibrating) {
            vibrating = true
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600), 0), attributes)
            }.onFailure { signal(AudioSignal.VIBRATION_FAILED) }
        }
        if (recovery.exhausted) return
        if (focused == Focus.NONE && now >= focusRetryAt && focusRequests < 3) acquireFocus()
        if (focused != Focus.HELD) return
        val current = player
        when {
            current == null && now >= retryAt -> preparePlayer()
            current != null && !ready && now >= preparedBy -> broken(AudioSignal.PREPARE_TIMEOUT)
            current != null && ready -> {
                if (!runCatching { current.isPlaying }.getOrDefault(false)) broken(AudioSignal.PLAYBACK_STOPPED)
                else applyVolume()
            }
        }
    }

    private fun acquireFocus() {
        focusRequests++
        val alarmId = owner?.id ?: return
        lateinit var focusRequest: AudioFocusRequest
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (request === focusRequest && owner?.id == alarmId) onFocusChange(change)
            }, handler)
            .build()
        request = focusRequest
        when (runCatching { manager.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> focused = Focus.HELD
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                focused = Focus.DELAYED
                signal(AudioSignal.FOCUS_DELAYED)
            }
            else -> {
                abandonFocus()
                focusRetryAt = SystemClock.elapsedRealtime() + 2_000
                signal(if (focusRequests >= 3) AudioSignal.FOCUS_UNAVAILABLE else AudioSignal.FOCUS_RETRY)
            }
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focused = Focus.HELD
                signal(AudioSignal.FOCUS_GAINED)
                tick()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focused = Focus.TRANSIENT
                releasePlayer()
                releasePreparationWakeLock()
                signal(AudioSignal.FOCUS_PAUSED)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                releasePlayer()
                releasePreparationWakeLock()
                abandonFocus()
                focused = Focus.LOST
                signal(AudioSignal.FOCUS_LOST)
            }
        }
    }

    private fun preparePlayer() {
        keepAwakeForPreparation()
        val uri = if (recovery.useFallback) fallback else source ?: fallback
        try {
            val instance = MediaPlayer()
            player = instance
            ready = false
            preparedBy = SystemClock.elapsedRealtime() + 8_000
            instance.setAudioAttributes(attributes)
            instance.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            instance.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (focused != Focus.HELD || suspendedForCall) {
                    releasePlayer()
                    return@setOnPreparedListener
                }
                try {
                    ready = true
                    it.isLooping = true
                    if (startedAt == null) startedAt = SystemClock.elapsedRealtime()
                    applyVolume()
                    if (player !== it) return@setOnPreparedListener
                    it.start()
                    releasePreparationWakeLock()
                    signal(AudioSignal.PLAYBACK_STARTED)
                } catch (_: RuntimeException) {
                    broken(AudioSignal.PLAYER_ERROR)
                }
            }
            instance.setOnErrorListener { failing, what, extra ->
                if (player === failing) {
                    val reason = when {
                        what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> AudioSignal.MEDIA_SERVER_DIED
                        extra == MediaPlayer.MEDIA_ERROR_IO -> AudioSignal.PLAYER_IO_ERROR
                        else -> AudioSignal.PLAYER_ERROR
                    }
                    broken(reason)
                }
                true
            }
            instance.setOnCompletionListener {
                if (player === it) broken(AudioSignal.PLAYBACK_STOPPED)
            }
            instance.setDataSource(context, uri)
            instance.prepareAsync()
        } catch (_: Exception) {
            broken(AudioSignal.SOURCE_UNAVAILABLE)
        }
    }

    private fun applyVolume() {
        val run = owner ?: return
        val current = player ?: return
        if (!ready) return
        val elapsed = SystemClock.elapsedRealtime() - (startedAt ?: return)
        val rampMillis = run.plan.rampSeconds * 1_000L
        val ramp = if (rampMillis == 0L) 1f else (elapsed.toFloat() / rampMillis).coerceIn(0.05f, 1f)
        val multiplier = if (run.state == RunState.MISSION_RUNNING && run.plan.duckDuringMission) 0.25f else 1f
        val volume = run.plan.volume * ramp * multiplier
        runCatching { current.setVolume(volume, volume) }.onFailure { broken(AudioSignal.PLAYER_ERROR) }
    }

    private fun broken(reason: AudioSignal) {
        signal(reason)
        releasePlayer()
        recovery = recovery.failed(canFallback = source != null)
        if (recovery.exhausted) {
            releasePreparationWakeLock()
            abandonFocus()
            signal(AudioSignal.RECOVERY_EXHAUSTED)
        } else {
            // The failed player may already have released its own wake lock.
            keepAwakeForPreparation()
            retryAt = SystemClock.elapsedRealtime() + recovery.retryDelayMillis
            if (recovery.useFallback && recovery.failures == 1) signal(AudioSignal.USING_FALLBACK)
        }
    }

    private fun signal(event: AudioSignal) { owner?.let { report(it.id, event) } }

    private fun releasePlayer() {
        val current = player
        player = null
        ready = false
        if (current != null) runCatching { current.release() }
    }

    private fun abandonFocus() {
        val previous = request
        request = null
        focused = Focus.NONE
        if (previous != null) runCatching { manager.abandonAudioFocusRequest(previous) }
    }

    private fun keepAwakeForPreparation() {
        preparationWakeLock.acquire(13_000)
    }

    private fun releasePreparationWakeLock() {
        if (preparationWakeLock.isHeld) runCatching { preparationWakeLock.release() }
    }

    private fun stopVibration() {
        if (vibrating) runCatching { vibrator.cancel() }
        vibrating = false
    }

    fun stop() {
        owner = null
        monitor?.cancel()
        monitor = null
        if (routeRegistered) {
            routeRegistered = false
            runCatching { manager.unregisterAudioDeviceCallback(routes) }
        }
        releasePlayer()
        releasePreparationWakeLock()
        abandonFocus()
        stopVibration()
        focused = Focus.NONE
        focusRequests = 0
        focusRetryAt = 0L
        retryAt = 0L
        startedAt = null
        suspendedForCall = false
    }
}

enum class AudioSignal(val detail: String? = null) {
    PLAYBACK_STARTED,
    PLAYBACK_STOPPED,
    PLAYER_ERROR,
    MEDIA_SERVER_DIED,
    PLAYER_IO_ERROR,
    SOURCE_UNAVAILABLE,
    PREPARE_TIMEOUT,
    USING_FALLBACK,
    RECOVERY_EXHAUSTED("Не удалось восстановить звук будильника. Проверьте аудиовыход."),
    FOCUS_DELAYED,
    FOCUS_PAUSED,
    FOCUS_GAINED,
    FOCUS_RETRY,
    FOCUS_UNAVAILABLE("Android не предоставил аудиофокус для звонка."),
    FOCUS_LOST("Android отозвал аудиофокус. Звук этого звонка приостановлен."),
    CALL_PAUSED,
    VOLUME_ZERO("Системная громкость будильника равна нулю."),
    ROUTE_CHANGED,
    ROUTE_MONITOR_UNAVAILABLE,
    VIBRATION_FAILED,
}

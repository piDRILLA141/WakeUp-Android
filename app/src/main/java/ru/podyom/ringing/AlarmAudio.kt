package ru.podyom.ringing

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.*
import kotlinx.coroutines.*
import ru.podyom.R
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.core.*

/** Owns exactly one player, focus request and vibration. Never changes system stream volume. */
class AlarmAudio(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var player: MediaPlayer? = null
    private var ramp: Job? = null
    private var level = 0f
    private var multiplier = 1f
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            // A call or other exclusive focus request must remain usable. Signal the limitation.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                player?.pause(); AlarmNotifications.failure(context, "Звук временно прерван другим приложением или звонком")
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) player?.start()
        }.build()
    fun start(run: AlarmRun, privateAlarm: PrivateAlarm?) {
        stop()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            AlarmNotifications.failure(context, "Не удалось получить аудиофокус. Проверьте громкость будильника")
        fun load(uri: Uri): MediaPlayer = MediaPlayer().apply {
            try {
                setAudioAttributes(attributes); setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK); setDataSource(context, uri); isLooping = true
                prepare(); setVolume(0f, 0f); start()
            } catch (e: Exception) { release(); throw e }
        }
        val fallback = Uri.parse("android.resource://${context.packageName}/${R.raw.dawn}")
        player = try { load(privateAlarm?.soundUri?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: fallback) }
        catch (_: Exception) { try { load(fallback) } catch (_: Exception) { AlarmNotifications.failure(context, "Ошибка воспроизведения встроенного сигнала"); null } }
        player?.setOnErrorListener { _, _, _ -> AlarmNotifications.failure(context, "Воспроизведение прервано. Откройте звонок"); false }
        if (run.plan.vibrate) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600), 0), attributes)
        multiplier = if (run.state == RunState.MISSION_RUNNING && run.plan.duckDuringMission) 0.25f else 1f
        ramp = scope.launch {
            val ticks = (run.plan.rampSeconds * 5).coerceAtLeast(1)
            for (i in 1..ticks) { level = run.plan.volume * i / ticks; applyVolume(); delay(200) }
        }
    }
    private fun applyVolume() { player?.setVolume(level * multiplier, level * multiplier) }
    fun duck(enabled: Boolean) { multiplier = if (enabled) 0.25f else 1f; applyVolume() }
    fun stop() { ramp?.cancel(); ramp = null; player?.release(); player = null; vibrator.cancel(); manager.abandonAudioFocusRequest(focus) }
}

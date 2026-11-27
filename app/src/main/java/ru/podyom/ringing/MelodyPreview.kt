package ru.podyom.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One foreground preview player. Real alarms take it out of use before acquiring focus. */
class MelodyPreview(private val context: Context, private val report: (Boolean) -> Unit) {
    data class Status(val source: String? = null, val message: String? = null)

    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    private var player: MediaPlayer? = null
    private var request: AudioFocusRequest? = null
    private var alarmActive = false
    private val timeout = Runnable { stop() }

    fun setAlarmActive(active: Boolean) {
        alarmActive = active
        if (active) stop()
    }

    fun toggle(key: String, uri: Uri, volume: Float) {
        if (state.value.source == key) {
            stop()
            return
        }
        stop()
        if (alarmActive || manager.mode in setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            state.value = Status(message = "Прослушивание доступно после звонка.")
            return
        }
        lateinit var focus: AudioFocusRequest
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (request === focus && change < 0) stop()
            }, handler).build()
        request = focus
        if (runCatching { manager.requestAudioFocus(focus) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Android не разрешил предпрослушивание.")
            return
        }

        try {
            val instance = MediaPlayer()
            player = instance
            state.value = Status(source = key)
            instance.setAudioAttributes(attributes)
            instance.setOnPreparedListener {
                if (player === it && !alarmActive) {
                    try {
                        val level = volume.coerceIn(0f, 1f)
                        it.setVolume(level, level)
                        it.start()
                        report(true)
                    } catch (_: RuntimeException) { fail() }
                }
            }
            instance.setOnCompletionListener { if (player === it) stop() }
            instance.setOnErrorListener { failing, _, _ -> if (player === failing) fail(); true }
            instance.setDataSource(context, uri)
            instance.prepareAsync()
            handler.postDelayed(timeout, 12_000L)
        } catch (_: Exception) { fail() }
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        val old = player
        player = null
        old?.let { runCatching { it.release() } }
        val focus = request
        request = null
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        state.value = Status()
    }

    private fun fail(message: String = "Мелодия недоступна. При звонке прозвучит встроенный резервный сигнал.") {
        stop()
        state.value = Status(message = message)
        report(false)
    }
}

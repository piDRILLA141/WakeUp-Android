package ru.podyom

import android.media.MediaPlayer
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import ru.podyom.core.AlarmPlan
import ru.podyom.core.AlarmRun
import ru.podyom.core.PrivateAlarm
import ru.podyom.core.RingingPriority
import ru.podyom.core.WakeMachine
import ru.podyom.ringing.AlarmAudio
import ru.podyom.ringing.AudioSignal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class AlarmAudioTest {
    @Test fun stoppedFirstPlayerRecoversAndNewerRingsPlayWithoutOverlapping() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val instances = mutableListOf<MediaPlayer>()
        val reports = mutableListOf<Pair<String, AudioSignal>>()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(500, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> instances += player }
        val audio = AlarmAudio(context, scope) { id, signal -> reports += id to signal }
        val looper = shadowOf(Looper.getMainLooper())
        fun advance() = looper.idleFor(2, TimeUnit.SECONDS)
        val runs = mutableListOf<AlarmRun>()
        fun start(index: Int) {
            val at = 1_000L + index * 600_000L
            val pending = AlarmRun("run-$index", AlarmPlan("plan-$index", vibrate = false, rampSeconds = 0), at)
            runs += WakeMachine.due(pending, 0, at)
            val selected = RingingPriority.next(runs)!!
            assertEquals(pending.id, selected.id)
            audio.start(selected, if (index == 0) PrivateAlarm(soundUri = "content://test/alarm") else PrivateAlarm())
            advance()
            assertTrue(instances.last().isPlaying)
            assertEquals(1, instances.count { runCatching { it.isPlaying }.getOrDefault(false) })
        }

        try {
            start(0)
            val first = instances.single()
            shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0)
            advance()
            assertTrue(instances.size >= 2)
            assertTrue(instances.last().isPlaying)
            assertTrue(reports.contains("run-0" to AudioSignal.MEDIA_SERVER_DIED))
            assertTrue(reports.contains("run-0" to AudioSignal.USING_FALLBACK))

            // An unexpected stop of the replacement must not carry over to the next ring.
            instances.last().pause()
            for (index in 1..3) start(index)
            assertEquals(setOf("run-0", "run-1", "run-2", "run-3"),
                reports.filter { it.second == AudioSignal.PLAYBACK_STARTED }.map { it.first }.toSet())
        } finally {
            audio.stop()
            scope.cancel()
        }
        assertTrue(instances.none { runCatching { it.isPlaying }.getOrDefault(false) })
    }
}

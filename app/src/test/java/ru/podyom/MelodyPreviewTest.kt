package ru.podyom

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.core.net.toUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import ru.podyom.core.PrivateAlarm
import ru.podyom.data.codec
import ru.podyom.ringing.Melodies
import ru.podyom.ringing.MelodyPreview
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class MelodyPreviewTest {
    @Test fun keepsLegacyFileUrisAndStableBundledIds() {
        val context = RuntimeEnvironment.getApplication()
        val old = codec.decodeFromString<PrivateAlarm>("""{"soundUri":"content://fixture/sound"}""")
        assertEquals("dawn", old.melodyId)
        assertEquals("content://fixture/sound", Melodies.source(context, old).toString())
        val updated = old.copy(soundUri = "", melodyId = "pulse")
        val restored = codec.decodeFromString<PrivateAlarm>(codec.encodeToString(updated))
        assertEquals(Melodies.uri(context, checkNotNull(Melodies.tone("pulse"))), Melodies.source(context, restored))
        assertEquals(4, Melodies.bundled.map { it.id }.distinct().size)
    }

    @Test fun switchesPreviewReleasesItOnFocusLossAndNeverCompetesWithRealRing() {
        val context = RuntimeEnvironment.getApplication()
        val preview = MelodyPreview(context) {}
        val created = mutableListOf<MediaPlayer>()
        val main = shadowOf(Looper.getMainLooper())
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(6_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> created += player }
        try {
            preview.toggle("dawn", Melodies.uri(context, Melodies.default), .3f)
            main.idleFor(100, TimeUnit.MILLISECONDS)
            assertTrue(created.single().isPlaying)
            preview.toggle("signal", Melodies.uri(context, Melodies.escalationDefault), .3f)
            main.idleFor(100, TimeUnit.MILLISECONDS)
            assertEquals(1, created.count { runCatching { it.isPlaying }.getOrDefault(false) })
            audio.lastAudioFocusRequest.audioFocusRequest.onAudioFocusChangeListener
                .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertNull(preview.status.value.source)
            preview.toggle("signal", Melodies.uri(context, Melodies.escalationDefault), .3f)
            preview.setAlarmActive(true)
            val oldCount = created.size
            preview.toggle("blocked", "content://fixture/blocked".toUri(), .3f)
            main.idleFor(100, TimeUnit.MILLISECONDS)
            assertEquals(oldCount, created.size)
            assertTrue(created.none { runCatching { it.isPlaying }.getOrDefault(false) })
        } finally { preview.stop() }
    }
}

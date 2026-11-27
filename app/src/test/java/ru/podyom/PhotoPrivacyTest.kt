package ru.podyom

import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.podyom.alarm.AlarmEngine
import ru.podyom.core.*
import ru.podyom.data.*
import ru.podyom.missions.PhotoOutcome
import ru.podyom.missions.processAndClose
import ru.podyom.missions.processPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class PhotoPrivacyTest {
    private fun pattern(seed: Long): PhotoMatcher.Frame {
        val random = java.util.Random(seed)
        val pixels = ByteArray(240 * 240) { 114 }
        repeat(95) {
            val x = 6 + random.nextInt(206)
            val y = 6 + random.nextInt(206)
            val color = (28 + random.nextInt(190)).toByte()
            for (row in y until y + random.nextInt(19) + 5) {
                for (col in x until x + random.nextInt(16) + 5) pixels[row * 240 + col] = color
            }
        }
        return PhotoMatcher.Frame(240, 240, pixels)
    }

    @Test fun repeatedMatchesNonMatchesErrorsAndCancellationCloseFramesAndLeaveNoFiles() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val roots = listOf(File(app.applicationInfo.dataDir), File(app.createDeviceProtectedStorageContext().applicationInfo.dataDir),
            app.filesDir, app.cacheDir, app.createDeviceProtectedStorageContext().filesDir)
        fun files() = roots.flatMap { root -> root.walkTopDown().filter(File::isFile).map(File::getAbsolutePath).toList() }.toSet()
        val reference = PhotoReference(requireNotNull(PhotoMatcher.extract(pattern(51)).template))
        val before = files()
        var closed = 0
        fun frame(): AutoCloseable = AutoCloseable { closed++ }
        repeat(4) {
            val accepted = processAndClose(frame()) { processPhoto(pattern(51), reference) { false } } as PhotoOutcome.Compared
            val rejected = processAndClose(frame()) { processPhoto(pattern(120), reference) { false } } as PhotoOutcome.Compared
            assertTrue(accepted.result.accepted)
            assertFalse(rejected.result.accepted)
            assertThrows(CancellationException::class.java) {
                processAndClose(frame()) { processPhoto(pattern(51), reference) { true } }
            }
            assertThrows(IllegalStateException::class.java) { processAndClose(frame()) { error("simulated unreadable frame") } }
        }
        assertEquals(16, closed)
        assertEquals(before, files())
    }

    @Test fun referenceIsPrivateAndSurvivesEditingOnlyWhileAnActiveRunNeedsIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val user = shadowOf(app.getSystemService(UserManager::class.java))
        user.setUserUnlocked(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        var clock = System.currentTimeMillis()
        val engine = AlarmEngine(app, clock = { clock })
        val plan = AlarmPlan("ref-alarm", missions = listOf(MissionSpec(MissionKind.PHOTO, 1)))
        val template = PhotoReference(requireNotNull(PhotoMatcher.extract(pattern(51)).template))
        engine.save(plan, PrivateAlarm(), testAt = clock + 500, photoDraft = template)
        val first = engine.snapshot().single()
        val id = requireNotNull(engine.profile(first.plan.privateKey)?.photoId)
        assertEquals(template, PhotoReferences(app).get(id))
        assertTrue(app.runtime.dao().alarms().none { it.json.contains(id) })
        user.setUserUnlocked(false)
        assertNull(app.photoReferences.get(id))
        val directBootRing = WakeMachine.startMission(first.copy(state = RunState.RINGING), clock)
        assertEquals(MissionKind.MATH, WakeMachine.fallback(directBootRing, clock).mission.kind)
        user.setUserUnlocked(true)
        clock += 500
        engine.event(first.id, first.version)
        engine.save(plan, PrivateAlarm(), testAt = clock + 5_000)
        assertNotNull(app.photoReferences.get(id))
        engine.update(first.id) { run, now -> WakeMachine.cancel(run, now) }
        assertNull(app.photoReferences.get(id))
        assertTrue(app.privateDb.dao().photoKeys().isEmpty())
    }
}

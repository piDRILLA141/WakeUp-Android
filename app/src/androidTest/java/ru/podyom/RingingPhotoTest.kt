package ru.podyom

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.podyom.core.*
import ru.podyom.data.model
import ru.podyom.data.row
import ru.podyom.ringing.RingingActivity

@RunWith(AndroidJUnit4::class)
class RingingPhotoTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun opensARealRingingActivityRecreatesItAndFallsBackWithoutPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as App
        val now = System.currentTimeMillis()
        val run = AlarmRun(UUID.randomUUID().toString(),
            AlarmPlan("device-photo-fixture", missions = listOf(MissionSpec(MissionKind.PHOTO, 1))),
            now, state = RunState.RINGING, firedAt = now)
        runBlocking { app.runtime.dao().putRun(run.row()) }
        val intent = Intent(context, RingingActivity::class.java).putExtra("runId", run.id)
        try {
            ActivityScenario.launch<RingingActivity>(intent).use { scenario ->
                fun waitForText(value: String) {
                    compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
                }
                waitForText("Начать задание")
                compose.onNodeWithText("Начать задание").performClick()
                waitForText("Заменить математикой")
                scenario.recreate()
                waitForText("Заменить математикой")
                compose.onNodeWithText("Заменить математикой").performScrollTo().performClick()
                waitForText("Проверить")
                assertTrue(runBlocking { app.runtime.dao().run(run.id)?.model()?.fallbackMath == true })
            }
        } finally {
            runBlocking { app.engine.update(run.id) { current, time -> WakeMachine.cancel(current, time) } }
        }
    }
}

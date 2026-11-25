package ru.podyom

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.core.*
import ru.podyom.data.row
import ru.podyom.ringing.RingingActivity
import ru.podyom.wakecheck.CheckRoute
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class, qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeActivityRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun storedCheck(app: App, deadline: Long, cycle: Int = 0, strict: Boolean = false): AlarmRun {
        val run = AlarmRun(UUID.randomUUID().toString(), AlarmPlan(UUID.randomUUID().toString(), strictCheck = strict),
            deadline - 100_000, state = RunState.WAKE_CHECK_ACTIVE, cycle = cycle, deadline = deadline, eventAt = deadline)
        runBlocking { app.runtime.dao().putRun(run.row()) }
        return run
    }

    private fun pendingIntent(app: App, run: AlarmRun): Intent {
        AlarmNotifications.refreshChecks(app, listOf(run))
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        return shadowOf(manager.getNotification(run.id, 43).contentIntent).savedIntent
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tappingNotificationOpensItsCheckAfterRecreationAndUsesTheNextIntent() {
        val app = RuntimeEnvironment.getApplication() as App
        val first = storedCheck(app, System.currentTimeMillis() + 120_000)
        val second = storedCheck(app, System.currentTimeMillis() + 150_000, strict = true)
        val firstIntent = pendingIntent(app, first)
        assertEquals(first.checkKey, CheckRoute.from(firstIntent)?.key)
        assertEquals(first.deadline, runBlocking { app.engine.snapshot().first { it.id == first.id }.deadline })
        ActivityScenario.launch<RingingActivity>(firstIntent).use { scenario ->
            waitForText("ДА, Я ПРОСНУЛСЯ")
            scenario.recreate()
            waitForText("ДА, Я ПРОСНУЛСЯ")
            val secondIntent = pendingIntent(app, second)
            scenario.onActivity { it.onNewIntent(secondIntent) }
            waitForText("Ответ для подтверждения")
            scenario.recreate()
            waitForText("Ответ для подтверждения")
            compose.onNodeWithText("Ответ для подтверждения").performTextReplacement(
                Missions.math(second.seed + second.cycle + second.checkIndex, 1).answer.toString())
            compose.onNodeWithText("ДА, Я ПРОСНУЛСЯ").performClick()
            waitForText("Пробуждение подтверждено")
            val persisted = runBlocking { app.engine.snapshot() }
            assertEquals(RunState.WAKE_CHECK_ACTIVE, persisted.first { it.id == first.id }.state)
            assertFalse(persisted.any { it.id == second.id })
        }
    }

    @Test fun expiredAndMissingNotificationStayOnAnInformativeScreen() {
        val app = RuntimeEnvironment.getApplication() as App
        val old = storedCheck(app, System.currentTimeMillis() - 1)
        ActivityScenario.launch<RingingActivity>(pendingIntent(app, old)).use { scenario ->
            waitForText("Время подтверждения истекло")
            compose.onNodeWithText("ДА, Я ПРОСНУЛСЯ").assertDoesNotExist()
            val missing = old.copy(id = UUID.randomUUID().toString())
            val missingIntent = CheckRoute(missing.checkKey, missing.version, "notification").intent(app)
            scenario.onActivity { it.onNewIntent(missingIntent) }
            waitForText("Проверка не найдена")
        }
    }
}

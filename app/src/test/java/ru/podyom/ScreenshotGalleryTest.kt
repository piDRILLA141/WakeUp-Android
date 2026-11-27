package ru.podyom

import android.Manifest
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog
import ru.podyom.core.*
import ru.podyom.missions.PracticePanel
import ru.podyom.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class, qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String, dialog: Boolean = false) = compose.runOnIdle {
        val view = if (dialog) checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window).decorView
            else compose.activity.window.decorView
        saveScreenshot(view, name)
    }

    private fun textFits(label: String) {
        val node = compose.onNodeWithText(label)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout for $label", layouts.isNotEmpty())
        layouts.forEach { layout ->
            for (line in 0 until layout.lineCount) {
                assertFalse("Ellipsized text: $label", layout.isLineEllipsized(line))
                assertTrue("Line goes beyond available width: $label (${layout.getLineRight(line)}/${layout.size.width})",
                    layout.getLineLeft(line) >= -2 && layout.getLineRight(line) <= layout.size.width + 2)
                assertTrue("Line goes beyond available height: $label", layout.getLineBottom(line) <= layout.size.height + 2)
            }
        }
    }

    @Test fun screenshotsHomeGroupsDiagnosticsSettingsAndSleep() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runBlocking {
            app.settings.seen()
            val group = app.engine.createGroup("Учебное расписание", cancelBackups = true)
            app.engine.save(AlarmPlan("sample-morning", hour = 6, minute = 20, groupId = group.id),
                PrivateAlarm(title = "Утренний звонок"))
        }
        val vm = MainViewModel(app)
        compose.setContent {
            PodyomTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    HomeScreen(vm)
                }
            }
        }
        compose.waitUntil(10_000) { vm.groups.value.isNotEmpty() && vm.profiles.value.isNotEmpty() }
        textFits("Начни свой день")
        save("home-small")
        compose.onNodeWithTag("home-content").performScrollToNode(hasText("Учебное расписание"))
        compose.onNodeWithText("Показать будильники").performScrollTo().performClick()
        compose.onNodeWithTag("home-content").performScrollToNode(hasText("Утренний звонок"))
        compose.onNodeWithText("Утренний звонок").assertIsDisplayed()
        save("groups-expanded")
        compose.onNodeWithText("Сон").performClick()
        compose.onNodeWithTag("home-content").performScrollToIndex(0)
        textFits("Режим сна")
        compose.onNodeWithText("Сохранить режим сна").assertExists()
        save("sleep")
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithTag("home-content").performScrollToIndex(0)
        textFits("Как тебе удобно")
        save("settings")
        compose.onNodeWithTag("home-content").performScrollToNode(hasText("Системное расписание"))
        textFits("Системное расписание")
        save("diagnostics")
    }

    @Test fun editorPhotoAndMelodiesFitSmallScreen() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        compose.setContent {
            PodyomTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    AlarmEditor(AlarmPlan("preview", missions = listOf(MissionSpec(MissionKind.PHOTO, 1))),
                        PrivateAlarm(title = "Тестовый будильник"), {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Название").performTextReplacement("Тестовая карточка")
        compose.onNodeWithText("Тестовая карточка").assertExists()
        compose.onNodeWithText("Сохранить будильник").assertIsDisplayed()
        save("editor-small-large-font", dialog = true)

        val editor = compose.onNodeWithTag("editor-content")
        editor.performScrollToNode(hasText("Мелодия · Рассвет"))
        compose.onNodeWithText("Мелодия · Рассвет").performClick()
        compose.onNodeWithText("Библиотека мелодий").assertIsDisplayed()
        save("melodies", dialog = true)
        compose.onNodeWithText("Готово").performClick()
        editor.performScrollToNode(hasText("Фотография"))
        compose.onNodeWithText("Фотография").assertExists()
        save("mission-settings", dialog = true)
        editor.performScrollToNode(hasText("Снять эталон"))
        compose.onNodeWithText("Снять эталон").performClick()
        compose.onNodeWithText("Разрешить камеру").assertIsDisplayed()
        save("photo-mission-permission", dialog = true)
        compose.onNodeWithText("Закрыть камеру").performClick()
    }

    @Test fun isolatedPracticeCanCompleteAndRetry() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val before = runBlocking { app.runtime.dao().runs() }
        var clock = 3_000L
        compose.setContent {
            PodyomTheme {
                Surface(Modifier.fillMaxSize()) {
                    PracticePanel(MissionSpec(count = 1, difficulty = 2), PrivateAlarm(), null, {},
                        now = { clock }, newSeed = { 77 })
                }
            }
        }
        compose.onNodeWithText("Проверить").assertIsDisplayed()
        save("practice")
        compose.onNodeWithText("Ответ").performTextReplacement(Missions.math(77, 2).answer.toString())
        compose.runOnIdle { clock = 5_100 }
        compose.onNodeWithText("Проверить").performClick()
        compose.onNodeWithText("Выполнено за 3 с").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Выполнено 0 из 1").assertIsDisplayed()
        assertEquals(before, runBlocking { app.runtime.dao().runs() })
    }
}

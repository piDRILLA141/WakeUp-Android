package ru.podyom

import android.Manifest
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import ru.podyom.missions.PracticeMission
import ru.podyom.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class, qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String, dialog: Boolean = false) = compose.runOnIdle {
        val view = if (dialog) checkNotNull(ShadowDialog.getLatestDialog()?.window).decorView
            else compose.activity.window.decorView
        saveScreenshot(view, name)
    }

    private fun textFits(label: String) {
        val node = compose.onNodeWithText(label)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout for $label", layouts.isNotEmpty())
        assertTrue("Text overflow: $label", layouts.none { it.hasVisualOverflow })
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

    @Test fun editorPhotoAndMelodiesFitSmallScreenAndPracticeIsIsolated() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val before = runBlocking { app.runtime.dao().runs() }
        var practice by mutableStateOf(false)
        var clock = 0L
        compose.setContent {
            PodyomTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    if (practice) PracticeMission(MissionSpec(count = 1, difficulty = 2), PrivateAlarm(), null, {},
                        now = { clock }, newSeed = { 77 })
                    else AlarmEditor(AlarmPlan("preview", missions = listOf(MissionSpec(MissionKind.PHOTO, 1))),
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

        compose.runOnIdle { practice = true; clock = 3_000 }
        compose.onNodeWithText("Проверить").assertIsDisplayed()
        save("practice", dialog = true)
        compose.onNodeWithText("Ответ").performTextReplacement(Missions.math(77, 2).answer.toString())
        compose.runOnIdle { clock = 5_100 }
        compose.onNodeWithText("Проверить").performClick()
        compose.onNodeWithText("Выполнено за 3 с").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Выполнено 0 из 1").assertIsDisplayed()
        assertEquals(before, runBlocking { app.runtime.dao().runs() })
    }
}

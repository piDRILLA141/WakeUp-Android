package ru.podyom

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.podyom.core.*
import ru.podyom.ui.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=App::class,qualifiers="w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun editorRendersAndSavesChosenTime() {
        var saved:AlarmPlan?=null
        compose.setContent { PodyomTheme { AlarmEditor(AlarmPlan("preview",hour=6,minute=30,days=setOf(1,2,3,4,5)),PrivateAlarm(title="Новое утро"),{}, {p,_->saved=p}) } }
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextReplacement("645")
        compose.onNodeWithText("Сохранить будильник").performClick()
        compose.runOnIdle {assertNull(saved)}
        compose.onNodeWithText("Сохранить",useUnmergedTree=true).performClick()
        compose.runOnIdle {assertEquals(6,saved!!.hour);assertEquals(45,saved!!.minute)}
    }
    @Test fun homeRendersPersistedAlarmsAndBothThemes() {
        val app=org.robolectric.RuntimeEnvironment.getApplication() as App
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        kotlinx.coroutines.runBlocking {
            app.settings.seen()
            app.engine.save(AlarmPlan("morning",hour=6,minute=30,days=(1..7).toSet()),PrivateAlarm(title="Новое утро"))
            app.engine.save(AlarmPlan("walk",hour=8,minute=15,days=(1..7).toSet(),missions=listOf(MissionSpec(MissionKind.QR,1))),PrivateAlarm(title="Время двигаться",qr="podyom-demo"))
        }
        val vm=MainViewModel(app)
        var light by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {PodyomTheme(light) {HomeScreen(vm)}}
        compose.waitUntil(10_000) {compose.onAllNodesWithText("Новое утро").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Время двигаться").assertExists()
        val output=File("build/reports/screenshots").apply {mkdirs()}
        fun capture(name:String) {
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(output,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
        capture("home-dark.png")
        compose.runOnIdle {light=true}
        capture("home-light.png")
        compose.onNodeWithContentDescription("Создать будильник").performClick()
        compose.onNodeWithText("Название").performTextReplacement("Проверка сохранения ввода")
        compose.runOnIdle { vm.clock24(false) }
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !vm.settings.value.clock24
        }
        compose.mainClock.advanceTimeBy(2_200)
        compose.onNodeWithText("Проверка сохранения ввода").assertExists()
        compose.onNodeWithContentDescription("Закрыть редактор").performClick()
    }

    @Test fun smartTimeInputKeepsSelectionAndSupportsDeleteAndPaste() {
        var raw by androidx.compose.runtime.mutableStateOf("")
        compose.setContent {PodyomTheme {SmartTimeInput(raw,{raw=it})}}
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextInput("640")
        compose.onNodeWithText("Время · ЧЧ:ММ").assertTextContains("06:40")
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextClearance()
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextInput("6:40")
        compose.onNodeWithText("Время · ЧЧ:ММ").assertTextContains("6:40")
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextReplacement("2460")
        compose.runOnIdle {assertEquals("2460",raw);assertTrue(runCatching {SmartTime.parse(raw)}.isFailure)}
    }

    @Test fun activePersistedCheckAppearsInsideAppAndConfirmationRemovesDialog() {
        val app=org.robolectric.RuntimeEnvironment.getApplication() as App
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        val now=System.currentTimeMillis()
        val run=AlarmRun("check-ui",AlarmPlan("alarm-ui"),now-60_000,state=RunState.WAKE_CHECK_ACTIVE,eventAt=now+100_000,deadline=now+100_000)
        kotlinx.coroutines.runBlocking {app.settings.seen();app.runtime.dao().putRun(ru.podyom.data.RunRow(run.id,run.plan.id,run.state.name,run.eventAt,ru.podyom.data.codec.encodeToString(run)))}
        val vm=MainViewModel(app)
        compose.setContent {PodyomTheme {HomeScreen(vm)}}
        compose.waitUntil(10_000) {compose.onAllNodesWithText("ТЫ ТОЧНО ПРОСНУЛСЯ?").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Подтверди пробуждение, чтобы будильник не повторился").assertExists()
        compose.runOnIdle {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output=File("build/reports/screenshots").apply {mkdirs()}
            File(output,"wake-check.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithText("ДА, Я ПРОСНУЛСЯ").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("ТЫ ТОЧНО ПРОСНУЛСЯ?").fetchSemanticsNodes().isEmpty()}
        kotlinx.coroutines.runBlocking {assertEquals(RunState.COMPLETED,ru.podyom.data.codec.decodeFromString<AlarmRun>(app.runtime.dao().run(run.id)!!.json).state)}
    }

}

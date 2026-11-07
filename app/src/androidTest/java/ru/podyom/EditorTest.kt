package ru.podyom

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.podyom.core.*
import ru.podyom.ui.*

@RunWith(AndroidJUnit4::class)
class EditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editedTimeRequiresExplicitSaveConfirmation() {
        var saved: AlarmPlan? = null
        compose.setContent { PodyomTheme { AlarmEditor(AlarmPlan("test"),PrivateAlarm(),{}, {plan,_->saved=plan}) } }
        compose.onNodeWithText("Время · ЧЧ:ММ").performTextReplacement("06:45")
        compose.onNodeWithText("Сохранить будильник").performClick()
        compose.runOnIdle {assertNull(saved)}
        compose.onNodeWithText("Сохранить",useUnmergedTree=true).performClick()
        compose.runOnIdle {assertEquals(6,saved!!.hour);assertEquals(45,saved!!.minute)}
    }
    @Test fun closingEditorDoesNotCreateAlarm() {
        var saved=false;var closed=false
        compose.setContent { PodyomTheme { AlarmEditor(AlarmPlan("test"),PrivateAlarm(),{closed=true},{_,_->saved=true}) } }
        compose.onNodeWithContentDescription("Закрыть редактор").performClick()
        compose.runOnIdle {assertTrue(closed);assertFalse(saved)}
    }
}

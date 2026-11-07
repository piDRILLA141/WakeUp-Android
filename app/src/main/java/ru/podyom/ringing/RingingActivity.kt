package ru.podyom.ringing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import ru.podyom.ui.PodyomTheme

class RingingActivity:ComponentActivity() {
    private val vm by viewModels<RingingViewModel>()
    private var runId by mutableStateOf("")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); setShowWhenLocked(true);setTurnScreenOn(true)
        runId=intent.getStringExtra("runId")?:""
        setContent {PodyomTheme {RingingScreen(runId,vm,onClose={finish()})}}
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);runId=intent.getStringExtra("runId")?:""}
}

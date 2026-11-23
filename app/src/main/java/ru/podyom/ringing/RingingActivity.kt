package ru.podyom.ringing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.podyom.core.RingingPriority
import ru.podyom.ui.PodyomTheme

class RingingActivity:ComponentActivity() {
    private val vm by viewModels<RingingViewModel>()
    private var runId by mutableStateOf("")
    private var followRinging by mutableStateOf(false)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); setShowWhenLocked(true);setTurnScreenOn(true)
        runId=intent.getStringExtra("runId")?:""
        followRinging = intent.getBooleanExtra("followRinging", false)
        setContent {
            val runs by vm.runs.collectAsStateWithLifecycle()
            val active = if (followRinging) RingingPriority.next(runs)?.id ?: runId else runId
            PodyomTheme { key(active) { RingingScreen(active, vm, onClose = { finish() }) } }
        }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);runId=intent.getStringExtra("runId")?:"";followRinging=intent.getBooleanExtra("followRinging",false)}
}

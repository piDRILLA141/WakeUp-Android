package ru.podyom.ui

import android.os.Bundle
import android.content.Intent
import ru.podyom.alarm.SleepReminders
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (intent.action == SleepReminders.OPEN_SLEEP) vm.showSleep()
        setContent { val settings by vm.settings.collectAsStateWithLifecycle(); PodyomTheme(settings.light) {
            HomeScreen(vm)
            if (settings.readySeen && !settings.wakeSessionsSeen) {
                AlertDialog(
                    onDismissRequest = vm::markWakeSessionsSeen,
                    title = { Text("Одно задание для одного подъёма") },
                    text = { Text("Если обычные будильники звонят в пределах 90 минут одного дня, следующий заменит прежнее незавершённое задание. Это можно отключить в настройках.") },
                    confirmButton = { TextButton(onClick = vm::markWakeSessionsSeen) { Text("Понятно") } },
                )
            }
        } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == SleepReminders.OPEN_SLEEP) vm.showSleep()
    }

    override fun onStart() { super.onStart(); vm.restoreOnce() }
}

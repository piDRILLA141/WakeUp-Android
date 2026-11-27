package ru.podyom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.podyom.app
import ru.podyom.core.PrivateAlarm
import ru.podyom.ringing.Melodies

@Composable
internal fun MelodyLibrary(
    selected: PrivateAlarm, volume: Float, backupOnly: Boolean,
    onChoose: (String) -> Unit, onClose: () -> Unit, onSystem: () -> Unit, onFile: () -> Unit,
) {
    val context = LocalContext.current
    val player = context.app.melodyPreview
    val status by player.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.stop()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.stop() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (backupOnly) "Резервный сигнал" else "Библиотека мелодий") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Melodies.bundled.groupBy { it.category }.forEach { (category, tones) ->
                    Text(category.title, style = MaterialTheme.typography.titleSmall)
                    tones.forEach { tone ->
                        val checked = selected.soundUri.isBlank() && (Melodies.tone(selected.melodyId) ?: Melodies.default).id == tone.id
                        Column {
                            Row {
                                RadioButton(checked, onClick = { onChoose(tone.id) })
                                TextButton(onClick = { onChoose(tone.id) }) { Text(tone.name) }
                            }
                            TextButton(onClick = { player.toggle(tone.id, Melodies.uri(context, tone), volume) }) {
                                Text(if (status.source == tone.id) "Остановить · ${tone.name}" else "Прослушать · ${tone.name}")
                            }
                        }
                    }
                }
                if (!backupOnly) {
                    Text(Melodies.Category.CUSTOM.title, style = MaterialTheme.typography.titleSmall)
                    if (selected.soundUri.isNotBlank()) TextButton(onClick = {
                        player.toggle("custom", Melodies.source(context, selected), volume)
                    }) { Text(if (status.source == "custom") "Остановить выбранную мелодию" else "Прослушать выбранную мелодию") }
                    TextButton(onClick = { player.stop(); onSystem() }) { Text("Выбрать системную мелодию") }
                    TextButton(onClick = { player.stop(); onFile() }) { Text("Выбрать мелодию из файла") }
                }
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
}

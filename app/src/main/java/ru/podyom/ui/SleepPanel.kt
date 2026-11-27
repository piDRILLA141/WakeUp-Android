package ru.podyom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import ru.podyom.core.SleepSettings
import ru.podyom.core.SmartTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepPanel(config: SleepSettings, morning: Long?, onSave: (SleepSettings) -> Unit) {
    val context = LocalContext.current
    var time by rememberSaveable(config.hour, config.minute) { mutableStateOf("%02d:%02d".format(config.hour, config.minute)) }
    var enabled by rememberSaveable(config.enabled) { mutableStateOf(config.enabled) }
    var lead by rememberSaveable(config.leadMinutes) { mutableIntStateOf(config.leadMinutes) }
    val configuredDays = config.days.sumOf { 1 shl (it - 1) }
    var days by rememberSaveable(configuredDays) { mutableIntStateOf(configuredDays) }
    var error by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Режим сна", style = MaterialTheme.typography.titleLarge)
            morning?.let { Text("Ближайший утренний будильник · ${date(it)}") }
            ToggleRow("Напоминать о времени сна", enabled) { enabled = it }
            if (enabled) {
                Text("Желаемое время сна")
                SmartTimeInput(time, { time = it; error = false }, showError = error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, day ->
                        val bit = 1 shl index
                        FilterChip(days and bit != 0, { days = days xor bit; error = false }, label = { Text(day) })
                    }
                }
                Text("Уведомить заранее")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(lead == minutes, { lead = minutes }, label = { Text("$minutes мин") })
                    }
                }
                Text("Android может отложить обычное уведомление до выхода из энергосбережения.", style = MaterialTheme.typography.bodySmall)
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    Text("Уведомления выключены. Разрешите их в настройках приложения.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (error) Text("Проверьте время и выберите хотя бы один день", color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                val parsed = if (enabled) runCatching { SmartTime.parse(time) }.getOrNull() else java.time.LocalTime.of(config.hour, config.minute)
                if (parsed == null || days == 0 && enabled) error = true else {
                    val selected = (1..7).filter { days and (1 shl (it - 1)) != 0 }.toSet().ifEmpty { config.days }
                    onSave(SleepSettings(enabled, parsed.hour, parsed.minute, selected, lead))
                }
            }) { Text("Сохранить режим сна") }
        }
    }
}

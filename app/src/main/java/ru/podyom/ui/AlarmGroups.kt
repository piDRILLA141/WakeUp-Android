package ru.podyom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.podyom.core.AlarmGroup
import ru.podyom.core.AlarmPlan
import ru.podyom.core.PrivateAlarm

@Composable
fun GroupHeader(
    group: AlarmGroup, title: String, plans: List<AlarmPlan>, expanded: Boolean, busy: Boolean,
    onExpand: () -> Unit, enabled: (Boolean) -> Unit, manage: () -> Unit,
    createAlarm: () -> Unit, addMembers: () -> Unit, skip: () -> Unit, restore: () -> Unit,
    remove: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    val active = if (group.enabled) plans.count { it.enabled } else 0
                    Text("Активных будильников: $active из ${plans.size}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(group.enabled, enabled, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Расписание группы $title" })
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "Действия группы $title") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Настройки группы / переименовать") }, onClick = { menu = false; manage() })
                        DropdownMenuItem(text = { Text("Создать будильник в группе") }, onClick = { menu = false; createAlarm() })
                        DropdownMenuItem(text = { Text("Добавить существующие") }, onClick = { menu = false; addMembers() })
                        DropdownMenuItem(text = { Text("Пропустить завтра") }, onClick = { menu = false; skip() })
                        DropdownMenuItem(text = { Text("Отменить завтрашний пропуск") }, onClick = { menu = false; restore() })
                        DropdownMenuItem(text = { Text("Удалить группу") }, onClick = { menu = false; remove(false) })
                        DropdownMenuItem(text = { Text("Удалить группу и будильники") }, onClick = { menu = false; remove(true) })
                    }
                }
            }
            if (plans.isNotEmpty()) {
                TextButton(onClick = onExpand, enabled = !busy) { Text(if (expanded) "Свернуть" else "Показать будильники") }
            }
        }
    }
}

@Composable
fun GroupSettingsDialog(
    group: AlarmGroup?, title: String, plans: List<AlarmPlan>, profiles: Map<String, PrivateAlarm>, busy: Boolean,
    onClose: () -> Unit, onSave: (String, Boolean) -> Unit, onRemove: (String) -> Unit,
) {
    var name by rememberSaveable(group?.id) { mutableStateOf(title) }
    var smart by rememberSaveable(group?.id) { mutableStateOf(group?.cancelBackups ?: false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (group == null) "Новая группа" else "Настройки группы") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true)
                if (group != null) {
                    ToggleRow("Отменять резервные сигналы после подтверждения пробуждения", smart) { smart = it }
                    Text("Если ты уже подтвердил, что проснулся, остальные будильники этой группы в ближайшие 90 минут сегодня не будут звонить.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Следующий звонок группы может заменить прежнее задание и проверку. Отложенные вручную сигналы сохраняются.",
                        style = MaterialTheme.typography.bodySmall)
                    plans.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { plan ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d:%02d · %s".format(plan.hour, plan.minute, profiles[plan.privateKey]?.title ?: "Будильник"),
                                Modifier.weight(1f))
                            TextButton(onClick = { onRemove(plan.id) }, enabled = !busy) { Text("Исключить") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, smart) }, enabled = !busy && name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

@Composable
fun AddGroupAlarmsDialog(
    plans: List<AlarmPlan>, profiles: Map<String, PrivateAlarm>, busy: Boolean, onClose: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Добавить будильники") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (plans.isEmpty()) Text("Нет доступных будильников")
            plans.forEach { plan ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(plan.id in chosen, { selected ->
                        chosen = ArrayList(if (selected) chosen + plan.id else chosen - plan.id)
                    }, enabled = !busy)
                    Text("%02d:%02d · %s".format(plan.hour, plan.minute, profiles[plan.privateKey]?.title ?: "Будильник"))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onApply(chosen.toSet()) }, enabled = !busy && chosen.isNotEmpty()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}

@Composable
fun MoveGroupDialog(
    groups: List<AlarmGroup>, profiles: Map<String, PrivateAlarm>, busy: Boolean,
    onClose: () -> Unit, onApply: (String?) -> Unit,
) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Переместить в группу") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { onApply(null) }, enabled = !busy) { Text("Без группы") }
            groups.forEach { group ->
                TextButton(onClick = { onApply(group.id) }, enabled = !busy) { Text(profiles[group.id]?.title ?: "Группа") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}

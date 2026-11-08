package ru.podyom.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.podyom.BuildConfig
import ru.podyom.core.*
import ru.podyom.data.*
import ru.podyom.permissions.Readiness
import ru.podyom.ringing.RingingActivity
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(vm: MainViewModel) {
    val alarms by vm.alarms.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle(); val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle(); val nights by vm.sleep.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var readiness by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(settings.readySeen) { if(!settings.readySeen) readiness = true }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val tabs = listOf("Будильники", "Сон", "Статистика", "Настройки")
    val icons = listOf(Icons.Outlined.Alarm, Icons.Outlined.Bedtime, Icons.Outlined.Insights, Icons.Outlined.Settings)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar { tabs.forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], title) }, label = { Text(title, fontSize = 11.sp) }) } }
    }, floatingActionButton = { if (tab == 0 && !readiness) FloatingActionButton(onClick = { editor = "new" }) { Icon(Icons.Outlined.Add, "Создать будильник") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (readiness) {
                item { Readiness() }
                item { Button(onClick = { vm.test() }, modifier = Modifier.fillMaxWidth()) { Text("Проверить будильник через 10 секунд") } }
                item { OutlinedButton(onClick = { readiness = false; vm.seen() }, modifier = Modifier.fillMaxWidth()) { Text("Перейти к будильникам") } }
            } else when(tab) {
                0 -> {
                    item {
                        val hour = LocalTime.now().hour
                        Text(when(hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; else -> "Добрый вечер" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp)); Text("Начни свой день", style = MaterialTheme.typography.headlineLarge)
                        val nearest = runs.filter { it.state == RunState.SCHEDULED }.minByOrNull { it.eventAt }
                        Text(nearest?.let { "Следующий будильник через ${duration(it.eventAt - now)}" } ?: "Время для нового утра", style = MaterialTheme.typography.bodyLarge)
                    }
                    item { FilledTonalButton(onClick=vm::quick, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Через 20 минут")
                    } }
                    val readinessProblems = ru.podyom.permissions.readinessProblems(context)
                    if (readinessProblems.isNotEmpty()) item {
                        OutlinedCard(onClick = { readiness = true }) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Проверьте готовность", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                            Text(readinessProblems.joinToString("\n")); Text("Открыть проверку", color = MaterialTheme.colorScheme.primary)
                        } }
                    }
                    val diagnostics = rows.filter { it.diagnostic.isNotEmpty() }
                    if (diagnostics.isNotEmpty()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("Расписание требует внимания: ${diagnostics.first().diagnostic}", Modifier.padding(16.dp)) } }
                    if(runs.any { !it.terminal && it.state != RunState.SCHEDULED }) {
                        items(runs.filter { !it.terminal && it.state != RunState.SCHEDULED }, key = { "active-${it.id}" }) { run ->
                            Card(onClick = { context.startActivity(Intent(context, RingingActivity::class.java).putExtra("runId", run.id)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text(stateLabel(run.state), style = MaterialTheme.typography.titleLarge)
                                    Text(profiles[run.plan.privateKey]?.title ?: "Подъём")
                                    if(run.needsEvent) Text("Через ${duration(run.eventAt - now)}")
                                    Text("Открыть", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (alarms.isEmpty()) item { Card { Column(Modifier.padding(24.dp)) { Icon(Icons.Outlined.WbTwilight, null, Modifier.size(48.dp)); Spacer(Modifier.height(16.dp)); Text("Утро начинается здесь", style = MaterialTheme.typography.titleLarge); Text("Создайте первый будильник. Задание поможет встать, а проверка напомнит не возвращаться в постель.") } } }
                    items(alarms.sortedBy { p -> runs.firstOrNull { it.plan.id == p.id && it.state == RunState.SCHEDULED }?.eventAt ?: Long.MAX_VALUE }, key = { it.id }) { alarm ->
                        val profile = profiles[alarm.privateKey] ?: PrivateAlarm()
                        AlarmCard(alarm, profile, settings.clock24, edit = { editor = alarm.id }, toggle = { enabled ->
                            if(enabled) vm.enabled(alarm.id, true) else confirm = "Выключить «${profile.title}»? Активные проверки останутся доступны отдельно." to { vm.enabled(alarm.id, false) }
                        }, duplicate = { vm.duplicate(alarm, profile) }, skip = { confirm = "Пропустить только следующее срабатывание?" to { vm.skip(alarm.id) } }, delete = { confirm = "Удалить «${profile.title}» вместе с его активными проверками?" to { vm.delete(alarm.id) } })
                    }
                    item { Spacer(Modifier.height(76.dp)) }
                }
                1 -> {
                    item { Text("Время восстановиться", style = MaterialTheme.typography.headlineLarge); Text("Ручной дневник сна. Только ваши отметки.") }
                    item { val current = nights.firstOrNull { it.ended == 0L }; Card { Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(if(current == null) "Спокойной ночи" else "Сон начался ${date(current.started)}", style = MaterialTheme.typography.titleLarge); Button(onClick = vm::toggleSleep) { Text(if(current == null) "Начать сон" else "Я проснулся") } } } }
                    val completed = nights.filter { it.ended > 0 }
                    item { val week = completed.filter { it.started > now - 7 * 86400000L }; Text("Среднее за неделю: ${if(week.isEmpty()) "пока нет данных" else duration(week.map { it.ended-it.started }.average().toLong())}"); MiniBars(completed.take(7).reversed().map { (it.ended-it.started).toFloat() }) }
                    items(completed.take(30), key = { it.started }) { night -> Card { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(date(night.started)); Text(duration(night.ended-night.started), color = MaterialTheme.colorScheme.primary) } } }
                    item { Text("Запись микрофона и определение фаз сна не используются.", style = MaterialTheme.typography.bodySmall) }
                }
                2 -> {
                    item { Text("Твои пробуждения", style = MaterialTheme.typography.headlineLarge); Text("Статистика взаимодействий, а не оценка физиологического бодрствования.") }
                    item { var days by rememberSaveable { mutableIntStateOf(7) }; Row { listOf(7,30).forEach { n -> FilterChip(selected = days == n, onClick = { days=n }, label = { Text("$n дней") }, modifier = Modifier.padding(end=8.dp)) } }; val recent = runs.filter { it.firedAt >= now - days*86400000L && it.firedAt > 0 }; StatsCard(recent); MiniBars((days-1 downTo 0).map { back -> val d = LocalDate.now().minusDays(back.toLong()); recent.count { Instant.ofEpochMilli(it.firedAt).atZone(ZoneId.systemDefault()).toLocalDate() == d }.toFloat() }) }
                    item { ru.podyom.statistics.WakeCalendar(runs) }
                    items(runs.filter { it.firedAt > 0 || it.state == RunState.CANCELLED }.sortedByDescending { it.updatedAt }.take(50), key={it.id}) { r -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(date(r.updatedAt)); Text(stateLabel(r.state)); Text("Повторы: ${r.cycle} · Отложено: ${r.snoozes} · Без ответа: ${r.missedChecks}", style = MaterialTheme.typography.bodySmall); if(r.emergency) Text("Аварийное завершение", color = MaterialTheme.colorScheme.tertiary) } } }
                }
                3 -> {
                    item { Text("Как тебе удобно", style = MaterialTheme.typography.headlineLarge) }
                    item { ToggleRow("Светлая тема", settings.light, vm::light); ToggleRow("24-часовой формат", settings.clock24, vm::clock24) }
                    item { ru.podyom.permissions.WakeCheckSettings(settings.enhancedCheck,vm::enhanced) }
                    item { ru.podyom.permissions.ScheduleDiagnostics(runs, rows) }
                    item { Text("Язык: русский\nКнопки громкости: системное управление потоком будильника", style = MaterialTheme.typography.bodyMedium) }
                    item { Button(onClick = { readiness = true }, modifier = Modifier.fillMaxWidth()) { Text("Проверка разрешений") } }
                    item { OutlinedButton(onClick = { vm.test() }, modifier = Modifier.fillMaxWidth()) { Text("Будильник через 10 секунд") } }
                    if(BuildConfig.DEBUG) item { OutlinedButton(onClick = { vm.test(true) }, modifier = Modifier.fillMaxWidth()) { Text("Тест Wake Up Check · 10 / 10 секунд") } }
                    item { OutlinedButton(onClick = { export.launch("podyom-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Экспорт будильников") }; OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Импорт будильников") }; Text("Импорт добавляет выключенные копии. Файлы мелодий нужно выбрать заново.", style = MaterialTheme.typography.bodySmall) }
                    item { TextButton(onClick = { confirm = "Удалить историю пробуждений и сна? Активные события сохранятся." to vm::clearHistory }) { Text("Очистить историю") } }
                    item { Text("Подъём ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge); Text("Бесплатно. Без рекламы, аккаунтов и интернета.\nФотозадание, приседания и анализ звуков сна не включены: для них ещё нужна проверенная реализация.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    editor?.let { key ->
        val original = alarms.find { it.id == key }
        // Stable draft identity: the one-second countdown must never reset a new editor.
        val initialPlan = remember(key) { original ?: AlarmPlan(UUID.randomUUID().toString()) }
        val initialProfile = remember(key) { original?.let { profiles[it.privateKey] } ?: PrivateAlarm() }
        AlarmEditor(initialPlan, initialProfile, onClose = { editor = null }, onSave = { p, private -> vm.save(p, private) { editor = null } })
    }
    confirm?.let { (text, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Подтвердите действие") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
    ru.podyom.wakecheck.ActiveWakeCheckHost(runs,vm::confirmCheck,vm::cancelCheck)
}
@Composable fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onChange) } }
@Composable private fun AlarmCard(plan: AlarmPlan, profile: PrivateAlarm, clock24: Boolean, edit: () -> Unit, toggle: (Boolean) -> Unit, duplicate: () -> Unit, skip: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = edit, modifier = Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=if(plan.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(LocalTime.of(plan.hour, plan.minute).format(DateTimeFormatter.ofPattern(if(clock24) "HH:mm" else "hh:mm a", java.util.Locale.forLanguageTag("ru"))), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f), color = if(plan.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(plan.enabled, toggle)
        }
        Text(profile.title, style = MaterialTheme.typography.titleMedium)
        Text(if(plan.days.isEmpty()) "Один раз" else plan.days.sorted().joinToString(" · ") { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1] }, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text((if(plan.quick) "Обычное подтверждение" else plan.missions.joinToString(" → ") { it.kind.title }) + if(plan.wakeCheck) " · Проверка пробуждения" else "", style = MaterialTheme.typography.bodySmall, modifier=Modifier.weight(1f))
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия будильника") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Дублировать на 10 минут позже") }, onClick = { menu=false; duplicate() }); DropdownMenuItem(text = { Text("Пропустить следующий") }, onClick = { menu=false; skip() }, enabled = plan.enabled); DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu=false; delete() }) } }
        }
    } }
}
@Composable private fun StatsCard(runs: List<AlarmRun>) { Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Сработало: ${runs.size}"); Text("Подтверждено: ${runs.count { it.state == RunState.COMPLETED && it.plan.wakeCheck }}"); Text("Повторных звонков: ${runs.sumOf { it.cycle }}"); Text("Откладываний: ${runs.sumOf { it.snoozes }}"); Text("Без ответа на проверку: ${runs.sumOf { it.missedChecks }}"); val times = runs.filter { it.missionFinishedAt > it.firedAt }.map { it.missionFinishedAt-it.firedAt }; Text("Среднее время до выполнения: ${if(times.isEmpty()) "нет данных" else duration(times.average().toLong())}") } } }
@Composable private fun MiniBars(values: List<Float>) { val color = MaterialTheme.colorScheme.primary; Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical=12.dp)) { if(values.isNotEmpty()) { val step = size.width/values.size; val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f); values.forEachIndexed { i, v -> drawLine(color, Offset(step*(i+.5f), size.height), Offset(step*(i+.5f), size.height*(1-v/max)), strokeWidth=step*.5f) } } } }
fun date(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", java.util.Locale.forLanguageTag("ru")))
fun stateLabel(s: RunState) = when(s) { RunState.SCHEDULED -> "Запланирован"; RunState.RINGING -> "Пора вставать"; RunState.MISSION_RUNNING -> "Задание выполняется"; RunState.WAITING_WAKE_CHECK -> "Проверка впереди"; RunState.WAKE_CHECK_ACTIVE -> "Ты точно проснулся?"; RunState.RE_RINGING -> "Повторный звонок"; RunState.SNOOZED -> "Отложен"; RunState.COMPLETED -> "Завершён"; RunState.CANCELLED -> "Отменён" }

package ru.podyom.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import ru.podyom.ringing.AudioSignal
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
import androidx.lifecycle.repeatOnLifecycle
import ru.podyom.wakecheck.CheckRoute
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
    val groups by vm.groups.collectAsStateWithLifecycle()
    var groupDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var moveTo by rememberSaveable { mutableStateOf<String?>(null) }
    var addingMembers by rememberSaveable { mutableStateOf<String?>(null) }
    var skipGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedGroups by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val alarms by vm.alarms.collectAsStateWithLifecycle(); val runs by vm.runs.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle(); val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle(); val nights by vm.sleep.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var readiness by rememberSaveable { mutableStateOf(false) }
    val busy by vm.scheduleBusy.collectAsStateWithLifecycle()
    val audioEvents by vm.audioEvents.collectAsStateWithLifecycle()
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var scheduleIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var scheduleOption by rememberSaveable { mutableStateOf("") }
    val completed by vm.scheduleCompleted.collectAsStateWithLifecycle()
    var acknowledged by rememberSaveable { mutableIntStateOf(completed) }
    LaunchedEffect(completed) {
        if (acknowledged != completed) {
            acknowledged = completed
            scheduleOption = ""
            scheduleIds = arrayListOf()
            skipGroup = null
            selectedIds = arrayListOf()
        }
    }
    val selecting = selectedIds.isNotEmpty()
    val ringing = RingingPriority.ordered(runs)
    fun select(id: String) {
        if (!busy) selectedIds = ArrayList(if (id in selectedIds) selectedIds - id else selectedIds + id)
    }
    fun openSchedule(ids: Collection<String>, option: String, groupId: String? = null) {
        skipGroup = groupId
        scheduleIds = ArrayList(ids)
        scheduleOption = option
    }
    BackHandler(selecting && !busy) { selectedIds = arrayListOf() }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val calendarNow = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                calendarNow.longValue = now
                delay(60_000L - Math.floorMod(now, 60_000L))
            }
        }
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(settings.readySeen) { if(!settings.readySeen) readiness = true }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val diagnosticsExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(vm::exportDiagnostics)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val tabs = listOf("Будильники", "Сон", "Статистика", "Настройки")
    val icons = listOf(Icons.Outlined.Alarm, Icons.Outlined.Bedtime, Icons.Outlined.Insights, Icons.Outlined.Settings)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (selecting && tab == 0) TopAppBar(
            title = { Text("Выбрано: ${selectedIds.size}") },
            actions = {
                TextButton(onClick = { selectedIds = ArrayList(alarms.map { it.id }) }, enabled = !busy) { Text("Выбрать все") }
                TextButton(onClick = { selectedIds = arrayListOf() }, enabled = !busy) { Text("Отмена") }
            },
        )
    }, bottomBar = {
        NavigationBar { tabs.forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { if (!busy) { tab = i; if (i != 0) selectedIds = arrayListOf() } }, icon = { Icon(icons[i], title) }, label = { Text(title, fontSize = 11.sp) }) } }
    }, floatingActionButton = { if (tab == 0 && !readiness && !selecting) FloatingActionButton(onClick = { editor = "new" }) { Icon(Icons.Outlined.Add, "Создать будильник") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (readiness) {
                item { Readiness() }
                item { ru.podyom.permissions.ScheduleDiagnostics(runs, rows) }
                item { Button(onClick = { vm.test() }, modifier = Modifier.fillMaxWidth()) { Text("Проверить будильник через 10 секунд") } }
                item { OutlinedButton(onClick = { readiness = false; vm.seen() }, modifier = Modifier.fillMaxWidth()) { Text("Перейти к будильникам") } }
            } else when(tab) {
                0 -> {
                    item {
                        val hour = LocalTime.now().hour
                        Text(when(hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; else -> "Добрый вечер" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp)); Text("Начни свой день", style = MaterialTheme.typography.headlineLarge)
                        val nearest = runs.filter { it.state == RunState.SCHEDULED }.minByOrNull { it.eventAt }
                        Text(nearest?.let { "Следующий будильник через ${duration(it.eventAt - calendarNow.longValue)}" } ?: "Время для нового утра", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (selecting) item {
                        if (alarms.any { it.id in selectedIds && !it.quick }) {
                            Button(onClick = { groupDialog = "new" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Объединить в группу")
                            }
                            if (groups.isNotEmpty()) OutlinedButton(onClick = { moveTo = "" }, enabled = !busy) {
                                Text("Переместить в группу")
                            }
                        }
                        Button(onClick = { openSchedule(selectedIds, "skip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Пропустить завтра")
                        }
                        OutlinedButton(onClick = { openSchedule(selectedIds, "disable") }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Выключить полностью") }
                    }
                    if (!selecting) item { FilledTonalButton(onClick=vm::quick, modifier=Modifier.fillMaxWidth()) {
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
                    if (!selecting && ringing.size > 1) item {
                        OutlinedButton(onClick = {
                            confirm = "Завершить текущие звонки (${ringing.size})? Незавершённые задания останутся в истории. Будущие сигналы и проверки сохранятся." to { vm.endCurrentRings(ringing.map { it.id }.toSet()) }
                        }) { Text("Завершить текущие звонки") }
                    }
                    if (!selecting && runs.any { !it.terminal && it.state != RunState.SCHEDULED }) {
                        items(runs.filter { !it.terminal && it.state != RunState.SCHEDULED }, key = { "active-${it.id}" }) { run ->
                            Card(onClick = { context.startActivity(if (run.state == RunState.WAKE_CHECK_ACTIVE) CheckRoute(run.checkKey, run.version, "list").intent(context) else Intent(context, RingingActivity::class.java).putExtra("runId", run.id)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    val status = when {
                                        run.sounding && run.id != ringing.firstOrNull()?.id -> "В очереди · не завершён"
                                        run.sounding -> "Текущий звонок"
                                        else -> stateLabel(run.state)
                                    }
                                    Text(status, style = MaterialTheme.typography.titleLarge)
                                    Text(date(run.ringingSince ?: run.scheduledAt), style = MaterialTheme.typography.bodySmall)
                                    Text(profiles[run.plan.privateKey]?.title ?: "Подъём")
                                    if(run.needsEvent) Text("Через ${duration(run.eventAt - calendarNow.longValue)}")
                                    Text("Открыть", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (alarms.isEmpty()) item { Card { Column(Modifier.padding(24.dp)) { Icon(Icons.Outlined.WbTwilight, null, Modifier.size(48.dp)); Spacer(Modifier.height(16.dp)); Text("Утро начинается здесь", style = MaterialTheme.typography.titleLarge); Text("Создайте первый будильник. Задание поможет встать, а проверка напомнит не возвращаться в постель.") } } }
                    val scheduled = runs.filter { it.state == RunState.SCHEDULED }.associateBy { it.plan.id }
                    val ordered = alarms.sortedBy { scheduled[it.id]?.eventAt ?: Long.MAX_VALUE }
                    val members = ordered.groupBy { it.groupId }
                    fun androidx.compose.foundation.lazy.LazyListScope.alarmItems(plans: List<AlarmPlan>) {
                        items(plans, key = { it.id }) { alarm ->
                            val profile = profiles[alarm.privateKey] ?: PrivateAlarm()
                            val next = scheduled[alarm.id]
                            val today = Instant.ofEpochMilli(calendarNow.longValue).atZone(ZoneId.systemDefault()).toLocalDate()
                            AlarmCard(alarm, profile, settings.clock24, next, today, selecting, alarm.id in selectedIds,
                                edit = { if (selecting) select(alarm.id) else editor = alarm.id },
                                longPress = { select(alarm.id) }, toggle = { enabled ->
                                    if (enabled) vm.enabled(alarm.id, true) else openSchedule(listOf(alarm.id), "choose")
                                }, duplicate = { vm.duplicate(alarm, profile) },
                                undoSkip = { day -> vm.undoSkip(alarm.id, day) },
                                delete = { confirm = "Удалить «${profile.title}» вместе с его активными проверками?" to { vm.delete(alarm.id) } },
                                move = if (!alarm.quick && groups.isNotEmpty()) ({ moveTo = alarm.id }) else null,
                                restoreBackup = { vm.restoreBackup(alarm.id) })
                        }
                    }
                    alarmItems(members[null].orEmpty())
                    groups.sortedBy { profiles[it.id]?.title }.forEach { group ->
                        val plans = members[group.id].orEmpty()
                        val opened = group.id in expandedGroups
                        item(key = "group-${group.id}") {
                            GroupHeader(group, profiles[group.id]?.title ?: "Группа", plans, opened || selecting,
                                busy || selecting,
                                onExpand = { expandedGroups = ArrayList(if (opened) expandedGroups - group.id else expandedGroups + group.id) },
                                enabled = { vm.groupEnabled(group.id, it) },
                                manage = { groupDialog = group.id },
                                createAlarm = { editor = "new:${group.id}" },
                                addMembers = { addingMembers = group.id },
                                skip = { openSchedule(plans.map { it.id }, "skip", group.id) },
                                restore = { vm.undoGroupSkip(group.id) },
                                remove = { deleteAlarms ->
                                    val text = if (deleteAlarms) "Удалить группу и все её будильники?" else "Удалить группу? Её будильники станут отдельными."
                                    confirm = text to { vm.removeGroup(group.id, deleteAlarms) }
                                })
                        }
                        if (opened || selecting) alarmItems(plans)
                    }
                    item { Spacer(Modifier.height(76.dp)) }
                }
                1 -> {
                    item { Text("Время восстановиться", style = MaterialTheme.typography.headlineLarge); Text("Ручной дневник сна. Только ваши отметки.") }
                    item { val current = nights.firstOrNull { it.ended == 0L }; Card { Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(if(current == null) "Спокойной ночи" else "Сон начался ${date(current.started)}", style = MaterialTheme.typography.titleLarge); Button(onClick = vm::toggleSleep) { Text(if(current == null) "Начать сон" else "Я проснулся") } } } }
                    val completed = nights.filter { it.ended > 0 }
                    item { val week = completed.filter { it.started > calendarNow.longValue - 7 * 86400000L }; Text("Среднее за неделю: ${if(week.isEmpty()) "пока нет данных" else duration(week.map { it.ended-it.started }.average().toLong())}"); MiniBars(completed.take(7).reversed().map { (it.ended-it.started).toFloat() }) }
                    items(completed.take(30), key = { it.started }) { night -> Card { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(date(night.started)); Text(duration(night.ended-night.started), color = MaterialTheme.colorScheme.primary) } } }
                    item { Text("Запись микрофона и определение фаз сна не используются.", style = MaterialTheme.typography.bodySmall) }
                }
                2 -> {
                    item { Text("Твои пробуждения", style = MaterialTheme.typography.headlineLarge); Text("Статистика взаимодействий, а не оценка физиологического бодрствования.") }
                    item { var days by rememberSaveable { mutableIntStateOf(7) }; Row { listOf(7,30).forEach { n -> FilterChip(selected = days == n, onClick = { days=n }, label = { Text("$n дней") }, modifier = Modifier.padding(end=8.dp)) } }; val recent = runs.filter { it.firedAt >= calendarNow.longValue - days*86400000L && it.firedAt > 0 }; StatsCard(recent); MiniBars((days-1 downTo 0).map { back -> val d = LocalDate.now().minusDays(back.toLong()); recent.count { Instant.ofEpochMilli(it.firedAt).atZone(ZoneId.systemDefault()).toLocalDate() == d }.toFloat() }) }
                    item { ru.podyom.statistics.WakeCalendar(runs) }
                    items(runs.filter { it.firedAt > 0 || it.state in setOf(RunState.CANCELLED, RunState.MISSED) }.sortedByDescending { it.updatedAt }.take(50), key={it.id}) { r -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(date(r.updatedAt)); Text(when (r.disposition) { "superseded" -> "Пропущен — заменён следующим будильником"; "backup" -> "Пропущен автоматически — пробуждение уже подтверждено"; else -> stateLabel(r.state) }); Text("Повторы: ${r.cycle} · Отложено: ${r.snoozes} · Без ответа: ${r.missedChecks}", style = MaterialTheme.typography.bodySmall); if(r.emergency) Text("Аварийное завершение", color = MaterialTheme.colorScheme.tertiary) } } }
                }
                3 -> {
                    item { Text("Как тебе удобно", style = MaterialTheme.typography.headlineLarge) }
                    item { ToggleRow("Светлая тема", settings.light, vm::light); ToggleRow("24-часовой формат", settings.clock24, vm::clock24) }
                    item {
                        ToggleRow("Не накапливать пропущенные будильники", settings.deduplicate, vm::deduplicate)
                        Text("Следующий обычный будильник заменит прежнее незавершённое задание в пределах 90 минут одного дня.", style = MaterialTheme.typography.bodySmall)
                    }
                    item { ru.podyom.permissions.WakeCheckSettings(settings.enhancedCheck,vm::enhanced) }
                    item { ru.podyom.permissions.ScheduleDiagnostics(runs, rows) }
                    if (audioEvents.isNotEmpty()) item {
                        OutlinedCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Диагностика звука", style = MaterialTheme.typography.titleMedium)
                                audioEvents.forEach { event ->
                                    val signal = AudioSignal.entries.firstOrNull { it.name == event.code }
                                    if (signal != null) Text("${date(event.timestampMs)} · ${audioLabel(signal)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item { Text("Язык: русский\nКнопки громкости: системное управление потоком будильника", style = MaterialTheme.typography.bodyMedium) }
                    item {
                        OutlinedButton(onClick = { diagnosticsExport.launch("podyom-diagnostics.json") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Экспорт диагностики")
                        }
                    }
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
    if (scheduleOption.isNotEmpty()) {
        val zone = ZoneId.systemDefault()
        val tomorrow = Instant.ofEpochMilli(calendarNow.longValue).atZone(zone).toLocalDate().plusDays(1)
        val eligible = alarms.filter { plan ->
            plan.id in scheduleIds && plan.enabled && tomorrow.toEpochDay() !in plan.skipEpochDays &&
                Schedule.hasOccurrenceOn(plan, tomorrow, zone,
                    runs.firstOrNull { it.plan.id == plan.id && it.state == RunState.SCHEDULED }?.scheduledAt)
        }
        val oneShot = eligible.any { it.days.isEmpty() }
        val explanation = when {
            scheduleOption == "disable" -> "Выключится будущее расписание выбранных будильников. Текущие звонки и начатые проверки сохранятся."
            eligible.isEmpty() -> "У выбранных будильников нет звонка на завтра или он уже пропущен."
            oneShot -> "Пропуск одноразового будильника отменит его единственное срабатывание. Пропуск можно отменить до этого времени."
            else -> "Завтрашние сигналы будут пропущены. Срабатывания на другие даты сохранятся."
        }
        fun closeSchedule() { scheduleOption = ""; scheduleIds = arrayListOf(); skipGroup = null }
        AlertDialog(
            onDismissRequest = { if (!busy) closeSchedule() },
            title = { Text(if (scheduleOption == "choose") "Что сделать с будильником?" else "Выбранные будильники: ${scheduleIds.size}") },
            text = {
                Column {
                    Text(explanation)
                    if (scheduleOption != "disable") {
                        TextButton(onClick = { vm.skipTomorrow(scheduleIds.toSet(), oneShot, skipGroup) },
                            enabled = !busy && eligible.isNotEmpty()) { Text("Пропустить завтра") }
                    }
                    if (scheduleOption != "skip") {
                        TextButton(onClick = { vm.disableSelected(scheduleIds.toSet()) }, enabled = !busy) {
                            Text("Выключить полностью")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::closeSchedule, enabled = !busy) { Text("Отмена") } },
        )
    }
    groupDialog?.let { id ->
        val group = groups.firstOrNull { it.id == id }
        if (id == "new" || group != null) GroupSettingsDialog(group, profiles[id]?.title.orEmpty(),
            alarms.filter { it.groupId == id }, profiles, busy, onClose = { groupDialog = null },
            onSave = { name, cancelBackups ->
                if (group == null) vm.createGroup(name, selectedIds.toSet()) {
                    groupDialog = null
                    selectedIds = arrayListOf()
                } else vm.editGroup(id, name, cancelBackups) { groupDialog = null }
            },
            onRemove = { alarmId -> vm.moveToGroup(setOf(alarmId), null) {} },
            onSkip = { groupDialog = null; openSchedule(alarms.filter { it.groupId == id }.map { it.id }, "skip", id) },
            onUndoSkip = { vm.undoGroupSkip(id) })
    }
    addingMembers?.let { id ->
        AddGroupAlarmsDialog(alarms.filter { !it.quick && it.groupId != id }, profiles, busy,
            onClose = { addingMembers = null },
            onApply = { ids -> vm.moveToGroup(ids, id) { addingMembers = null } })
    }
    moveTo?.let { itemId ->
        val ids = if (itemId.isEmpty()) selectedIds.toSet() else setOf(itemId)
        MoveGroupDialog(groups, profiles, busy, onClose = { moveTo = null },
            onApply = { groupId -> vm.moveToGroup(ids, groupId) { moveTo = null; selectedIds = arrayListOf() } })
    }
    editor?.let { key ->
        val original = alarms.find { it.id == key }
        // Stable draft identity: the one-second countdown must never reset a new editor.
        val initialPlan = remember(key) {
            original ?: AlarmPlan(UUID.randomUUID().toString(), groupId = key.substringAfter("new:", "").ifBlank { null })
        }
        val initialProfile = remember(key) { original?.let { profiles[it.privateKey] } ?: PrivateAlarm() }
        AlarmEditor(initialPlan, initialProfile, onClose = { editor = null }, onSave = { p, private -> vm.save(p, private) { editor = null } })
    }
    confirm?.let { (text, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Подтвердите действие") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
    val ringingForeground by vm.ringingForeground.collectAsStateWithLifecycle()
    val confirming by vm.confirmingCheck.collectAsStateWithLifecycle()
    if (!ringingForeground) {
        ru.podyom.wakecheck.ActiveWakeCheckHost(runs, vm::confirmCheck, vm::cancelCheck, confirming = confirming)
    }
}
@Composable fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onChange) } }
@Composable
private fun AlarmCard(
    plan: AlarmPlan, profile: PrivateAlarm, clock24: Boolean, next: AlarmRun?, today: LocalDate,
    selecting: Boolean, selected: Boolean, edit: () -> Unit, longPress: () -> Unit,
    toggle: (Boolean) -> Unit, duplicate: () -> Unit, undoSkip: (Long) -> Unit, delete: () -> Unit,
    move: (() -> Unit)?, restoreBackup: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val skipDates = plan.skipEpochDays.map(LocalDate::ofEpochDay).filter { plan.days.isEmpty() || it >= today }.sorted()
    val autoSkipToday = plan.automaticSkipDay == today.toEpochDay()
    val autoTime = plan.oneShotAt ?: today.atTime(plan.hour, plan.minute).atZone(ZoneId.systemDefault())
        .withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
    val formatter = DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag("ru"))
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = edit, onLongClick = longPress, onLongClickLabel = "Выбрать будильник"),
        colors = CardDefaults.cardColors(containerColor = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            plan.enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(LocalTime.of(plan.hour, plan.minute).format(DateTimeFormatter.ofPattern(
                    if (clock24) "HH:mm" else "hh:mm a", java.util.Locale.forLanguageTag("ru"),
                )), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f),
                    color = if (plan.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (selecting) Checkbox(checked = selected, onCheckedChange = null)
                else Switch(plan.enabled, toggle)
            }
            Text(profile.title, style = MaterialTheme.typography.titleMedium)
            Text(if (plan.days.isEmpty()) "Один раз" else plan.days.sorted().joinToString(" · ") {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1]
            }, color = MaterialTheme.colorScheme.primary)
            skipDates.forEach { skipDate ->
                Text(if (skipDate == today.plusDays(1)) "Завтра пропущен" else
                    "Пропуск: " + skipDate.format(formatter),
                    color = MaterialTheme.colorScheme.primary)
            }
            if (autoSkipToday) Text("Резервный сигнал на сегодня отменён", color = MaterialTheme.colorScheme.primary)
            if (next != null) Text("Следующий звонок · ${date(next.eventAt)}", style = MaterialTheme.typography.bodySmall)
            else if (skipDates.isNotEmpty() && plan.days.isEmpty()) Text("Единственное срабатывание пропущено", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text((if (plan.quick) "Обычное подтверждение" else plan.missions.joinToString(" → ") { it.kind.title }) +
                    if (plan.wakeCheck) " · Проверка пробуждения" else "", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                if (!selecting) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия будильника") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Дублировать на 10 минут позже") }, onClick = { menu = false; duplicate() })
                        skipDates.forEach { day ->
                            DropdownMenuItem(text = { Text(if (skipDates.size == 1) "Отменить пропуск" else "Отменить пропуск · ${day.format(formatter)}") },
                                onClick = { menu = false; undoSkip(day.toEpochDay()) })
                        }
                        if (move != null) DropdownMenuItem(text = { Text("Переместить в группу") }, onClick = { menu = false; move() })
                        if (autoSkipToday && autoTime > System.currentTimeMillis()) {
                            DropdownMenuItem(text = { Text("Восстановить резервный сигнал") }, onClick = { menu = false; restoreBackup() })
                        }
                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; delete() })
                    }
                }
            }
        }
    }
}

private fun audioLabel(signal: AudioSignal): String = when (signal) {
    AudioSignal.PLAYBACK_STARTED -> "Началось воспроизведение"
    AudioSignal.PLAYBACK_STOPPED -> "Плеер остановился"
    AudioSignal.PLAYER_ERROR -> "Ошибка плеера"
    AudioSignal.MEDIA_SERVER_DIED -> "Медиаслужба Android перезапустилась"
    AudioSignal.PLAYER_IO_ERROR -> "Ошибка чтения звука"
    AudioSignal.SOURCE_UNAVAILABLE -> "Звуковой файл недоступен"
    AudioSignal.PREPARE_TIMEOUT -> "Истекло время подготовки плеера"
    AudioSignal.USING_FALLBACK -> "Выбран встроенный сигнал"
    AudioSignal.FOCUS_DELAYED -> "Android отложил предоставление аудиофокуса"
    AudioSignal.FOCUS_PAUSED -> "Временная потеря аудиофокуса"
    AudioSignal.FOCUS_GAINED -> "Аудиофокус восстановлен"
    AudioSignal.FOCUS_RETRY -> "Аудиофокус запрошен повторно"
    AudioSignal.CALL_PAUSED -> "Звук приостановлен для вызова"
    AudioSignal.ROUTE_CHANGED -> "Изменились доступные аудиовыходы"
    AudioSignal.ROUTE_MONITOR_UNAVAILABLE -> "Наблюдение за аудиовыходами недоступно"
    AudioSignal.VIBRATION_FAILED -> "Не удалось включить вибрацию"
    else -> signal.detail ?: "Ошибка звука"
}
@Composable private fun StatsCard(runs: List<AlarmRun>) { Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Сработало: ${runs.size}"); Text("Подтверждено: ${runs.count { it.state == RunState.COMPLETED && it.plan.wakeCheck }}"); Text("Повторных звонков: ${runs.sumOf { it.cycle }}"); Text("Откладываний: ${runs.sumOf { it.snoozes }}"); Text("Без ответа на проверку: ${runs.sumOf { it.missedChecks }}"); val times = runs.filter { it.missionFinishedAt > it.firedAt }.map { it.missionFinishedAt-it.firedAt }; Text("Среднее время до выполнения: ${if(times.isEmpty()) "нет данных" else duration(times.average().toLong())}") } } }
@Composable private fun MiniBars(values: List<Float>) { val color = MaterialTheme.colorScheme.primary; Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical=12.dp)) { if(values.isNotEmpty()) { val step = size.width/values.size; val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f); values.forEachIndexed { i, v -> drawLine(color, Offset(step*(i+.5f), size.height), Offset(step*(i+.5f), size.height*(1-v/max)), strokeWidth=step*.5f) } } } }
fun date(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", java.util.Locale.forLanguageTag("ru")))
fun stateLabel(s: RunState) = when(s) { RunState.SCHEDULED -> "Запланирован"; RunState.RINGING -> "Пора вставать"; RunState.MISSION_RUNNING -> "Задание выполняется"; RunState.WAITING_WAKE_CHECK -> "Проверка впереди"; RunState.WAKE_CHECK_ACTIVE -> "Ты точно проснулся?"; RunState.RE_RINGING -> "Повторный звонок"; RunState.SNOOZED -> "Отложен"; RunState.COMPLETED -> "Завершён"; RunState.CANCELLED -> "Отменён"; RunState.MISSED -> "Пропущен" }

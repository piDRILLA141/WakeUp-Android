package ru.podyom.ui

import androidx.core.net.toUri
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.podyom.core.*
import ru.podyom.data.codec
import ru.podyom.missions.CodeScanner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun AlarmEditor(initial: AlarmPlan, privateInitial: PrivateAlarm, onClose: () -> Unit, onSave: (AlarmPlan, PrivateAlarm) -> Unit) {
    var planJson by rememberSaveable(initial.id) { mutableStateOf(codec.encodeToString(initial)) }
    val p = remember(planJson) { codec.decodeFromString<AlarmPlan>(planJson) }
    fun change(value: AlarmPlan) { planJson = codec.encodeToString(value) }
    var privateJson by rememberSaveable(initial.id) { mutableStateOf(codec.encodeToString(privateInitial)) }
    val private = remember(privateJson) { codec.decodeFromString<PrivateAlarm>(privateJson) }
    fun profile(value: PrivateAlarm) { privateJson = codec.encodeToString(value) }
    var time by rememberSaveable { mutableStateOf("%02d:%02d".format(p.hour, p.minute)) }
    var attemptedSave by remember {mutableStateOf(false)}
    var scan by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); profile(private.copy(soundUri = uri.toString())) } catch (_: Exception) { error = "Не удалось сохранить доступ к файлу" }
    } }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        @Suppress("DEPRECATION") val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if(uri != null) profile(private.copy(soundUri = uri.toString()))
    }
    fun submit() {
        attemptedSave=true
        try {
            val parsed = SmartTime.parse(time); time = SmartTime.normalize(time)
            val plan = p.copy(hour = parsed.hour, minute = parsed.minute, oneShotAt = if(parsed.hour==initial.hour && parsed.minute==initial.minute) p.oneShotAt else null); plan.validate()
            require(private.phrase.isNotBlank()) { "Введите фразу" }
            require(plan.missions.none { it.kind == MissionKind.QR } || private.qr.isNotEmpty()) { "Зарегистрируйте QR-код" }
            onSave(plan, private)
        } catch (e: Exception) { error = e.message ?: "Проверьте настройки" }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text("Твоё новое утро") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Закрыть редактор") } }) }, bottomBar = { Surface { Button(onClick = { saveConfirm = true }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Сохранить будильник") } } }) { inset ->
            LazyColumn(Modifier.fillMaxSize().padding(inset).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SmartTimeInput(time, { time = it;attemptedSave=false },showError=attemptedSave) }
                item { OutlinedTextField(private.title, { profile(private.copy(title = it.take(100))) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                if(!p.quick) item { Text("Повторение", style = MaterialTheme.typography.titleLarge); FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed { i, d -> FilterChip(p.days.contains(i+1), { change(p.copy(days = if(i+1 in p.days) p.days-(i+1) else p.days+(i+1), oneShotAt=null)) }, label = { Text(d) }) } }; Text(if(p.days.isEmpty()) "Один раз" else "Каждую выбранную неделю", style = MaterialTheme.typography.bodySmall) }
                item { HorizontalDivider(); Text("Сигнал", style = MaterialTheme.typography.titleLarge) }
                item { Text(if(private.soundUri.isEmpty()) "Встроенная мелодия «Рассвет»" else "Выбрана пользовательская мелодия"); FlowRow { TextButton(onClick = { profile(private.copy(soundUri = "")) }) { Text("Рассвет") }; TextButton(onClick = { systemPicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)) }) { Text("Системные") }; TextButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("Из файла") }; TextButton(onClick = {
                    if(player != null) { player?.release(); player = null } else try {
                        val uri = private.soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: "android.resource://${context.packageName}/${ru.podyom.R.raw.dawn}".toUri()
                        player = MediaPlayer().apply { setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()); setDataSource(context,uri); prepare(); setVolume(p.volume,p.volume); start(); setOnCompletionListener { it.release(); player=null } }
                    } catch (_: Exception) { player?.release(); player=null; error="Мелодия недоступна. При звонке будет использован «Рассвет»" }
                }) { Text(if(player == null) "Прослушать" else "Остановить") } } }
                item { Text("Уровень сигнала: ${(p.volume*100).toInt()}% от системной громкости"); Slider(p.volume, { change(p.copy(volume = it)) }, valueRange = .05f..1f); NumberSetting("Плавное усиление, секунд", p.rampSeconds, 0..120) { change(p.copy(rampSeconds=it)) }; ToggleRow("Вибрация", p.vibrate) { change(p.copy(vibrate=it)) }; ToggleRow("Приглушать во время задания", p.duckDuringMission) { change(p.copy(duckDuringMission=it)) } }
                if(!p.quick) item { HorizontalDivider(); Text("Задания по порядку", style = MaterialTheme.typography.titleLarge); Text("От 1 до 5 заданий. Для недоступной камеры или датчика есть явная замена математикой.", style = MaterialTheme.typography.bodySmall) }
                p.missions.forEachIndexed { index, m -> item(key="mission-$index") {
                    OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Задание ${index+1}", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { MissionKind.entries.forEach { kind -> FilterChip(m.kind == kind, { val list = p.missions.toMutableList(); list[index] = m.copy(kind=kind, count=when(kind) { MissionKind.QR -> 1; MissionKind.STEPS, MissionKind.SHAKE -> 10; else -> 3 }); change(p.copy(missions=list)) }, label={Text(kind.title)}) } }
                        fun update(spec: MissionSpec) { val list = p.missions.toMutableList(); list[index]=spec; change(p.copy(missions=list)) }
                        if(m.kind != MissionKind.QR) NumberSetting(if(m.kind == MissionKind.MEMORY) "Раундов" else "Количество", m.count, 1..if(m.kind == MissionKind.MATH) 20 else 50) { update(m.copy(count=it)) }
                        if(m.kind == MissionKind.MATH) FlowRow { listOf("Легко","Средне","Сложно").forEachIndexed { i,s -> FilterChip(m.difficulty==i+1,{update(m.copy(difficulty=i+1))},label={Text(s)},modifier=Modifier.padding(end=4.dp)) } }
                        if(m.kind == MissionKind.MEMORY) { NumberSetting("Размер сетки", m.grid, 2..4) { update(m.copy(grid=it)) }; NumberSetting("Длина последовательности", m.length, 2..12) { update(m.copy(length=it)) } }
                        if(p.missions.size>1) TextButton(onClick={change(p.copy(missions=p.missions.filterIndexed { i,_ -> i!=index }))}) { Text("Убрать задание") }
                    } }
                } }
                if(!p.quick && p.missions.size<5) item { OutlinedButton(onClick={change(p.copy(missions=p.missions+MissionSpec()))},modifier=Modifier.fillMaxWidth()) { Text("Добавить задание") } }
                if(p.missions.any { it.kind == MissionKind.TEXT }) item { OutlinedTextField(private.phrase, {profile(private.copy(phrase=it.take(500)))},label={Text("Фраза для набора")}, modifier=Modifier.fillMaxWidth()) }
                if(p.missions.any { it.kind == MissionKind.QR }) item { Button(onClick={scan=true}) { Text(if(private.qr.isEmpty()) "Зарегистрировать код камерой" else "Заменить зарегистрированный код") }; Text(if(private.qr.isEmpty()) "Код ещё не сохранён" else "Код сохранён локально. Другие коды не подойдут.") }
                if(!p.quick) item { HorizontalDivider(); Text("Повторно не засну", style=MaterialTheme.typography.titleLarge); ToggleRow("Проверить, что я проснулся",p.wakeCheck) {change(p.copy(wakeCheck=it))} }
                if(p.wakeCheck) {
                    item { Text("Задержка после задания"); FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf(1,3,5,10,15,20).forEach { minutes -> FilterChip(p.wakeDelaySeconds==minutes*60,{change(p.copy(wakeDelaySeconds=minutes*60))}, label={Text("$minutes мин")}) } }; NumberSetting("Своя задержка, минут",p.wakeDelaySeconds/60,1..1440) {change(p.copy(wakeDelaySeconds=it*60))} }
                    item { Text("Время на ответ"); FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf(30,60,100,120).forEach { seconds -> FilterChip(p.confirmSeconds==seconds,{change(p.copy(confirmSeconds=seconds))},label={Text("$seconds с")}) } }; ToggleRow("Усиленная проверка · пример",p.strictCheck) {change(p.copy(strictCheck=it))}; NumberSetting("Контрольных проверок",p.checkCount,1..3) {change(p.copy(checkCount=it))}; if(p.checkCount>1) NumberSetting("Между проверками, минут",p.laterCheckSeconds/60,1..1440) {change(p.copy(laterCheckSeconds=it*60))}; Text("Если ответа нет, сигнал зазвонит снова. После задания цикл продолжится.",style=MaterialTheme.typography.bodySmall) }
                }
                if(p.quick) item {
                    Text("Быстрый будильник отключается обычным подтверждением, без заданий и проверки пробуждения.")
                    OutlinedButton(onClick={change(p.copy(quick=false,missions=listOf(MissionSpec()),wakeCheck=true))}) {Text("Добавить задания и проверку")}
                }
                item { HorizontalDivider(); Text("Отложить",style=MaterialTheme.typography.titleLarge); NumberSetting("Максимум откладываний · 0 = выключено",p.snoozeLimit,0..10) {change(p.copy(snoozeLimit=it))}; NumberSetting("Интервал, минут",p.snoozeSeconds/60,1..60) {change(p.copy(snoozeSeconds=it*60))} }
                error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            }
        }
        if(scan) Dialog(onDismissRequest={scan=false},properties=DialogProperties(usePlatformDefaultWidth=false)) { Surface { Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) { Text("Наведите камеру на свой код",style=MaterialTheme.typography.headlineSmall); CodeScanner(onCode={profile(private.copy(qr=it));scan=false}, modifier=Modifier.weight(1f)); TextButton(onClick={scan=false}) {Text("Отмена")} } } }
        if(saveConfirm) AlertDialog(onDismissRequest={saveConfirm=false}, title={Text("Сохранить расписание?")},text={Text("Время: $time. Изменение заменит ближайшее запланированное срабатывание этого будильника.")},confirmButton={TextButton(onClick={saveConfirm=false;submit()}) {Text("Сохранить")}},dismissButton={TextButton(onClick={saveConfirm=false}) {Text("Отмена")}})
    }
}
@Composable fun NumberSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
                Icon(Icons.Outlined.Remove, "Уменьшить: $label")
            }
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
                Icon(Icons.Outlined.Add, "Увеличить: $label")
            }
        }
        Slider(value.toFloat(), { onChange(it.roundToInt().coerceIn(range)) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

package ru.podyom.ringing

import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.podyom.core.*
import ru.podyom.missions.*
import ru.podyom.ui.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable fun RingingScreen(id:String,vm:RingingViewModel,onClose:()->Unit) {
    val runs by vm.runs.collectAsStateWithLifecycle(); val run=runs.firstOrNull {it.id==id}
    val message by vm.message.collectAsStateWithLifecycle()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var emergency by rememberSaveable {mutableStateOf(false)}
    var phrase by rememberSaveable {mutableStateOf("")}
    val context=LocalContext.current
    var unlockRevision by remember {mutableIntStateOf(0)}
    var private by remember(id) {mutableStateOf<PrivateAlarm?>(null)}
    LaunchedEffect(run?.plan?.privateKey,unlockRevision) {run?.let {private=vm.profile(it.plan.privateKey)}}
    LaunchedEffect(Unit) {while(true) {now=System.currentTimeMillis();delay(250)}} // Display only. Deadline is a persisted system alarm.
    BackHandler {onClose()} // Leaving the screen never changes the durable state.
    Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("ПОДЪЁМ",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.displayLarge)
        Text(private?.title?:"Пора встречать утро",style=MaterialTheme.typography.headlineMedium)
        if(run==null) {Text("Загрузка состояния…"); TextButton(onClick=onClose) {Text("Закрыть экран")};return@Column}
        if(private==null) {
            Text("После перезагрузки разблокируйте телефон для доступа к вашей фразе, коду и мелодии.")
            Button(onClick={context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(context as ComponentActivity,object:KeyguardManager.KeyguardDismissCallback(){ override fun onDismissSucceeded(){unlockRevision++} })}) {Text("Разблокировать")}
        }
        when(run.state) {
            RunState.RINGING,RunState.RE_RINGING -> {
                Text(if(run.plan.quick) "Быстрый будильник · время вышло" else if(run.state==RunState.RE_RINGING) "Проверка осталась без ответа. Давай попробуем ещё раз." else "${run.plan.missions.size} заданий для бодрого старта")
                if(run.plan.quick) Button(onClick={vm.dismissQuick(id)},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text("Выключить будильник")}
                else Button(onClick={vm.start(id)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Начать задание")}
            }
            RunState.MISSION_RUNNING -> {
                Text("Задание ${run.missionIndex+1} из ${run.plan.missions.size} · ${run.mission.kind.title}",style=MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(progress={run.progress.toFloat()/run.mission.count},modifier=Modifier.fillMaxWidth())
                Text("Выполнено ${run.progress} из ${run.mission.count}")
                MissionPanel(run,private,onAnswer={vm.answer(id,run.version,it)},onMovement={vm.movement(id,run.missionIndex,run.cycle,run.mission.kind,it)},fallback={vm.fallback(id)})
            }
            RunState.WAITING_WAKE_CHECK -> {Text("Проверка впереди",style=MaterialTheme.typography.headlineSmall);Text("Вернёмся через ${duration(run.eventAt-now)}. Можно закрыть приложение — проверка сохранена в системном расписании.");OutlinedButton(onClick=onClose){Text("Продолжить утро")}}
            RunState.WAKE_CHECK_ACTIVE -> {
                ru.podyom.wakecheck.WakeCheckPanel(run,{answer->vm.confirm(id,answer)},{emergency=true})
            }
            RunState.SNOOZED -> {Text("Сигнал отложен на ${duration(run.eventAt-now)}");OutlinedButton(onClick=onClose){Text("Закрыть экран")}}
            RunState.COMPLETED,RunState.CANCELLED -> {Text(if(run.state==RunState.COMPLETED) "Хорошего тебе дня" else "Этот звонок завершён",style=MaterialTheme.typography.headlineLarge);Text("Следующее регулярное срабатывание остаётся в расписании.");Button(onClick=onClose){Text("Готово")}}
            RunState.SCHEDULED -> Text("Будильник запланирован на ${date(run.eventAt)}")
        }
        if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error)
        if(run.sounding && run.snoozes<run.plan.snoozeLimit) OutlinedButton(onClick={vm.snooze(id)},modifier=Modifier.fillMaxWidth()) {Text("Отложить · ${run.plan.snoozeSeconds/60} мин (${run.plan.snoozeLimit-run.snoozes} осталось)")}
        if(!run.terminal) TextButton(onClick={emergency=true}) {Text("Не могу выполнить · аварийное завершение")}
    } }
    ru.podyom.wakecheck.ActiveWakeCheckHost(runs,{rid,answer->vm.confirm(rid,answer)},vm::cancel,excludeId=id)
    if(emergency) AlertDialog(onDismissRequest={emergency=false},title={Text("Завершить текущий звонок и проверки?")},text={Column {Text("Если вам плохо или задание недоступно, можно остановиться. Введите «ЗАВЕРШИТЬ». Случай сохранится в истории без штрафов.");OutlinedTextField(phrase,{phrase=it},label={Text("Подтверждение")})}},confirmButton={TextButton(onClick={vm.cancel(id);emergency=false},enabled=phrase.trim()=="ЗАВЕРШИТЬ"){Text("Завершить")}},dismissButton={TextButton(onClick={emergency=false}){Text("Вернуться")}})
}
@Composable private fun MissionPanel(run:AlarmRun,private:PrivateAlarm?,onAnswer:(String)->Unit,onMovement:(Int)->Unit,fallback:()->Unit) {
    val spec=run.mission;val seed=Missions.challengeSeed(run)
    var input by rememberSaveable(run.id,seed) {mutableStateOf("")}
    when(spec.kind) {
        MissionKind.MATH,MissionKind.TEXT -> {
            Text(if(spec.kind==MissionKind.MATH) Missions.math(seed,spec.difficulty).text else private?.phrase?:"Разблокируйте телефон",style=MaterialTheme.typography.headlineSmall)
            OutlinedTextField(input,{input=it},label={Text(if(spec.kind==MissionKind.MATH) "Ответ" else "Введите фразу")},keyboardOptions=KeyboardOptions(keyboardType=if(spec.kind==MissionKind.MATH) KeyboardType.Number else KeyboardType.Text,autoCorrectEnabled=false),modifier=Modifier.fillMaxWidth())
            Button(onClick={onAnswer(input)},enabled=spec.kind==MissionKind.MATH || private!=null,modifier=Modifier.fillMaxWidth()){Text("Проверить")}
        }
        MissionKind.MEMORY -> MemoryPanel(seed,spec,onAnswer)
        MissionKind.QR -> {Text("Отсканируйте зарегистрированный код");if(private!=null) CodeScanner(onCode=onAnswer,modifier=Modifier.fillMaxWidth().height(330.dp)) else Text("Код защищён до первой разблокировки")}
        MissionKind.SHAKE,MissionKind.STEPS -> SensorMission(spec.kind,onMovement,fallback)
    }
}
@Composable private fun MemoryPanel(seed:Int,spec:MissionSpec,onAnswer:(String)->Unit) {
    val sequence=remember(seed){Missions.memory(seed,spec.grid,spec.length)}
    var showing by rememberSaveable(seed){mutableStateOf(true)}
    var highlighted by remember(seed){mutableIntStateOf(-1)}
    var entered by rememberSaveable(seed){mutableStateOf("")}
    var replay by rememberSaveable(seed){mutableIntStateOf(0)}
    var shownReplay by rememberSaveable(seed){mutableIntStateOf(-1)}
    LaunchedEffect(seed,replay) {if(shownReplay!=replay){showing=true;entered="";delay(400);for(tile in sequence){highlighted=tile;delay(600);highlighted=-1;delay(250)};showing=false;shownReplay=replay}}
    Text(if(showing) "Запомните порядок подсветки" else "Повторите последовательность")
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        repeat(spec.grid){ row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { repeat(spec.grid){ col -> val index=row*spec.grid+col
            Button(onClick={val list=(entered.takeIf{it.isNotEmpty()}?.split(',')?.map(String::toInt)?:emptyList())+index;entered=list.joinToString(",");if(list.size==sequence.size){onAnswer(entered);replay++}},enabled=!showing,modifier=Modifier.weight(1f).heightIn(min=56.dp),colors=ButtonDefaults.buttonColors(containerColor=if(index==highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,disabledContainerColor=if(index==highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)){Text("${index+1}")}
        } } }
    }
    TextButton(onClick={replay++}){Text("Показать ещё раз")}
}

package ru.podyom.wakecheck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.podyom.core.*

fun remainingText(deadline:Long,now:Long):String {
    val seconds=((deadline-now).coerceAtLeast(0)+999)/1000
    return "%02d:%02d".format(seconds/60,seconds%60)
}
@Composable fun WakeCheckPanel(run:AlarmRun,onConfirm:(String)->Unit,onEmergency:()->Unit) {
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var answer by rememberSaveable(run.id,run.checkIndex,run.cycle){mutableStateOf("")}
    LaunchedEffect(run.id,run.deadline){while(true){now=System.currentTimeMillis();delay(250)}}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("ТЫ ТОЧНО ПРОСНУЛСЯ?",style=MaterialTheme.typography.headlineMedium)
        Text("Подтверди пробуждение, чтобы будильник не повторился")
        Text(remainingText(run.deadline,now),style=MaterialTheme.typography.displayLarge,color=MaterialTheme.colorScheme.primary)
        if(run.plan.strictCheck) {
            Text(Missions.math(run.seed+run.checkIndex+run.cycle,1).text,style=MaterialTheme.typography.headlineMedium)
            OutlinedTextField(answer,{answer=it},label={Text("Ответ для подтверждения")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        }
        Button(onClick={onConfirm(answer)},enabled=now<run.deadline,modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text("ДА, Я ПРОСНУЛСЯ")}
        Text("Если не подтвердить, сигнал повторится",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=onEmergency) {Text("Аварийное завершение")}
    }
}
@Composable fun ActiveWakeCheckHost(runs:List<AlarmRun>,confirm:(String,String)->Unit,cancel:(String)->Unit,excludeId:String?=null) {
    val run=runs.filter {it.state==RunState.WAKE_CHECK_ACTIVE && it.id!=excludeId}.minByOrNull {it.deadline}?:return
    var emergency by rememberSaveable(run.id){mutableStateOf(false)}
    var phrase by rememberSaveable(run.id){mutableStateOf("")}
    var answerError by rememberSaveable(run.id,run.checkIndex,run.cycle){mutableStateOf(false)}
    AlertDialog(onDismissRequest={},confirmButton={},text={Column {
        WakeCheckPanel(run,{answer->
            if(run.plan.strictCheck && answer.toIntOrNull()!=Missions.math(run.seed+run.checkIndex+run.cycle,1).answer) answerError=true
            else {answerError=false;confirm(run.id,answer)}
        },{emergency=true})
        if(answerError) Text("Проверьте ответ",color=MaterialTheme.colorScheme.error)
    }})
    if(emergency) AlertDialog(onDismissRequest={emergency=false},title={Text("Завершить эту проверку?")},text={Column {
        Text("Введите ЗАВЕРШИТЬ. Остальные будильники сохранятся.")
        OutlinedTextField(phrase,{phrase=it},label={Text("Подтверждение завершения")})
    }},confirmButton={TextButton(onClick={cancel(run.id);emergency=false},enabled=phrase.trim()=="ЗАВЕРШИТЬ"){Text("Завершить")}},dismissButton={TextButton(onClick={emergency=false}){Text("Вернуться")}})
}

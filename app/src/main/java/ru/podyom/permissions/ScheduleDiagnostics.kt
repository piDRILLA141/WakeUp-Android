package ru.podyom.permissions

import android.app.NotificationManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.podyom.app
import ru.podyom.core.*
import ru.podyom.data.RunRow
import ru.podyom.ui.date

@Composable fun ScheduleDiagnostics(runs:List<AlarmRun>,rows:List<RunRow>) {
    val context=LocalContext.current
    var revision by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){delay(1500);revision++}}
    val system=remember(revision,runs){context.app.scheduler.nextSystemAlarm()}
    val next=runs.filter {it.state==RunState.SCHEDULED}.minByOrNull {it.eventAt}
    val event=runs.filter {it.needsEvent}.minByOrNull {it.eventAt}
    OutlinedCard {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Системное расписание",style=MaterialTheme.typography.titleLarge)
        Text("Следующий будильник приложения: ${next?.let {date(it.eventAt)}?:"нет"}")
        Text("Ближайшее событие приложения: ${event?.let {date(it.eventAt)+" · "+ru.podyom.ui.stateLabel(it.state)}?:"нет"}")
        Text("Следующее событие Android: ${system?.let {date(it.triggerTime)}?:"не зарегистрировано"}")
        Text("Владелец события Android: ${system?.showIntent?.creatorPackage?:"неизвестен"}")
        Text("Точное планирование: ${if(context.app.scheduler.allowed()) "доступно" else "запрещено"}")
        val manager=context.getSystemService(NotificationManager::class.java)
        Text("Полноэкранный показ: ${if(Build.VERSION.SDK_INT<34 || manager.canUseFullScreenIntent()) "разрешён" else "запрещён"}")
        val problems=readinessProblems(context)+rows.map {it.diagnostic}.filter {it.isNotBlank()}.distinct()
        if(event!=null && system==null) Text("Android не сообщает следующее системное событие. Проверьте разрешения и тест звонка.",color=MaterialTheme.colorScheme.error)
        problems.forEach {Text(it,color=MaterialTheme.colorScheme.error)}
        Text("Значок в строке состояния выбирает Android / OriginOS. Приложение не может принудительно включить его.",style=MaterialTheme.typography.bodySmall)
    }}
}
@Composable fun WakeCheckSettings(enhanced:Boolean,onChange:(Boolean)->Unit) {
    val context=LocalContext.current
    var tick by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){delay(1000);tick++}}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Заметность проверки пробуждения",style=MaterialTheme.typography.titleLarge)
        Text("В 0.2.0 добавлены отдельные каналы активной проверки. Ваши прежние каналы сохранены; проверьте звук и важность новых каналов в настройках уведомлений.",style=MaterialTheme.typography.bodySmall)
        Row {FilterChip(!enhanced,{onChange(false)},label={Text("Обычная")});Spacer(Modifier.width(8.dp));FilterChip(enhanced,{onChange(true)},label={Text("Усиленная")})}
        Text("Усиленная проверка использует заметный сигнал и вибрацию. Дополнительно можно разрешить карточку поверх других приложений. Она не перекрывает весь экран.")
        val allowed=remember(tick){Settings.canDrawOverlays(context)}
        Text("Показ поверх других приложений: ${if(allowed) "разрешён" else "не разрешён"}")
        if(enhanced) OutlinedButton(onClick={
            val intent=Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))
            try {context.startActivity(intent)} catch(_:ActivityNotFoundException) {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
        }) {Text("Настроить показ поверх приложений")}
        Text("Системные и защищённые экраны могут запрещать наложение. Без него проверка доступна через уведомление и экран приложения.",style=MaterialTheme.typography.bodySmall)
    }
}

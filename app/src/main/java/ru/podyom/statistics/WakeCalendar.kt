package ru.podyom.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.podyom.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun WakeCalendar(runs:List<AlarmRun>) {
    val today=LocalDate.now();val month=YearMonth.from(today);val zone=ZoneId.systemDefault()
    val confirmed=runs.filter {it.state==RunState.COMPLETED && it.plan.wakeCheck}.map {Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate()}.toSet()
    val offset=month.atDay(1).dayOfWeek.value-1
    Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru"))),style=MaterialTheme.typography.titleLarge)
        Row {listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach {Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)}}
        repeat((offset+month.lengthOfMonth()+6)/7) {week->Row {repeat(7){day->
            val number=week*7+day-offset+1
            val date=if(number in 1..month.lengthOfMonth()) month.atDay(number) else null
            val active=date in confirmed
            Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp).background(if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, CircleShape)
                .semantics {contentDescription=if(date==null) "" else "$number: ${if(active) "пробуждение подтверждено" else "нет подтверждения"}"},contentAlignment=Alignment.Center) {
                if(date!=null) Text(number.toString(),color=if(date==today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }}}
        Text("Отмечены дни с подтверждением проверки",style=MaterialTheme.typography.bodySmall)
        Text("Дней подряд: ${Statistics.streak(runs,today,zone)}",color=MaterialTheme.colorScheme.primary)
    }}
}

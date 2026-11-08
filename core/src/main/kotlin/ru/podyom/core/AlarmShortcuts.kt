package ru.podyom.core

import java.time.*

object AlarmShortcuts {
    fun later(source: AlarmPlan, newId: String, occurrence: Instant, zone: ZoneId): AlarmPlan {
        val time = if(source.days.isEmpty()) occurrence.plusSeconds(600).atZone(zone).toLocalTime() else LocalTime.of(source.hour, source.minute).plusMinutes(10)
        val rolls = source.hour * 60 + source.minute + 10 >= 1440
        return source.copy(id = newId, privateKey = newId, enabled = true, hour = time.hour, minute = time.minute,
            days = if(rolls) source.days.map { it % 7 + 1 }.toSet() else source.days,
            oneShotAt = if(source.days.isEmpty()) occurrence.plusSeconds(600).toEpochMilli() else null)
    }
    fun copyForNext(source:AlarmPlan,newId:String,now:Instant,zone:ZoneId,scheduledAt:Long?):AlarmPlan {
        if(source.days.isNotEmpty()) return later(source,newId,now,zone)
        val pending=scheduledAt?.takeIf {it+600_000>now.toEpochMilli()}
        if(pending!=null) return later(source,newId,Instant.ofEpochMilli(pending),zone)
        val time=LocalTime.of(source.hour,source.minute).plusMinutes(10)
        val copy=source.copy(id=newId,privateKey=newId,enabled=true,hour=time.hour,minute=time.minute,oneShotAt=null)
        val target=Schedule.next(copy,now,zone)
        val local=target.atZone(zone)
        return copy.copy(hour=local.hour,minute=local.minute,oneShotAt=target.toEpochMilli())
    }
    fun quick(id: String, now: Instant, zone: ZoneId): AlarmPlan {
        val at = now.plusSeconds(1200)
        val local = at.atZone(zone)
        return AlarmPlan(id, hour=local.hour, minute=local.minute, missions=emptyList(), wakeCheck=false,
            snoozeLimit=0, quick=true, oneShotAt=at.toEpochMilli())
    }
}

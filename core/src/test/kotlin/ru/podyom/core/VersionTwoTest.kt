package ru.podyom.core

import org.junit.Test
import org.junit.Assert.*
import java.time.*
import kotlinx.serialization.json.Json

class VersionTwoTest {
    @Test fun allTimeEntryExamplesNormalize() {
        mapOf("640" to "06:40","630" to "06:30","830" to "08:30","123" to "01:23","0640" to "06:40",
            "1830" to "18:30","2359" to "23:59","6:40" to "06:40","06:40" to "06:40"," 640 " to "06:40").forEach {(raw,expected)->assertEquals(expected,SmartTime.normalize(raw))}
    }
    @Test fun invalidAndIntermediateInputNeverBecomeArbitraryTimes() {
        listOf("","6","64","2460","2400","1260","12:99","-640","64:0","6:4","a640","12345","06::40").forEach {raw->
            assertTrue(raw,runCatching {SmartTime.parse(raw)}.isFailure)
        }
    }
    @Test fun formatterHasTotalMonotonicCursorMappings() {
        listOf("","6","64","640","0640","6:40","06:40","2460","24:60").forEach {raw->
            val p=SmartTime.present(raw)
            assertEquals(raw.length+1,p.originalToVisual.size);assertEquals(p.text.length+1,p.visualToOriginal.size)
            assertTrue(p.originalToVisual.all {it in 0..p.text.length});assertTrue(p.visualToOriginal.all {it in 0..raw.length})
            assertEquals(p.originalToVisual.sorted(),p.originalToVisual);assertEquals(p.visualToOriginal.sorted(),p.visualToOriginal)
            for(i in 0..raw.length) assertEquals(i,p.visualToOriginal[p.originalToVisual[i]])
        }
        assertEquals("06:40",SmartTime.present("640").text)
        assertEquals("18:30",SmartTime.present("1830").text)
    }
    @Test fun duplicateRetainsEveryIndividualOptionAndShiftsSunday() {
        val source=AlarmPlan("a",hour=23,minute=55,days=setOf(5,7),volume=.45f,strictCheck=true,
            missions=listOf(MissionSpec(MissionKind.MEMORY,2,3),MissionSpec(MissionKind.QR,1)))
        val copy=AlarmShortcuts.later(source,"b",Instant.parse("2026-11-08T20:55:00Z"),ZoneId.of("Europe/Moscow"))
        assertEquals(source.copy(id="b",privateKey="b",enabled=true,hour=0,minute=5,days=setOf(6,1)),copy)
        assertEquals(23,source.hour);assertEquals(setOf(5,7),source.days)
    }
    @Test fun duplicateOneShotPreservesActualDateIncludingFutureAfterMidnight() {
        val base=Instant.parse("2026-11-08T23:55:00Z")
        val source=AlarmPlan("a",hour=23,minute=55)
        val copy=AlarmShortcuts.later(source,"b",base,ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-11-09T00:05:00Z"),Schedule.next(copy,base.minusSeconds(86400),ZoneOffset.UTC))
    }
    @Test fun duplicateWithinDayAndQuickAreExact() {
        val source=AlarmPlan("a",hour=6,minute=30)
        val copy=AlarmShortcuts.later(source,"b",Instant.parse("2026-11-08T06:30:00Z"),ZoneOffset.UTC)
        assertEquals(6,copy.hour);assertEquals(40,copy.minute)
        val now=Instant.parse("2026-11-08T23:50:37Z")
        val quick=AlarmShortcuts.quick("q",now,ZoneOffset.UTC)
        assertEquals(0,quick.hour);assertEquals(10,quick.minute)
        assertEquals(now.plusSeconds(1200),Schedule.next(quick,now,ZoneOffset.UTC));assertTrue(quick.missions.isEmpty());assertFalse(quick.wakeCheck)
        val ringing=WakeMachine.due(AlarmRun("r",quick,quick.oneShotAt!!),0,quick.oneShotAt!!)
        assertEquals(ringing,WakeMachine.startMission(ringing,quick.oneShotAt!!))
        val done=WakeMachine.dismissQuick(ringing,quick.oneShotAt!!+1000)
        assertEquals(RunState.COMPLETED,done.state);assertFalse(done.needsEvent)
    }
    @Test fun oldSettingsAndAlarmJsonUseCompatibleDefaults() {
        val legacy="""{"id":"legacy","hour":6,"minute":40,"missions":[{"kind":"MATH","count":3}]}"""
        val restored=Json.decodeFromString<AlarmPlan>(legacy)
        restored.validate();assertFalse(restored.quick);assertNull(restored.oneShotAt);assertTrue(restored.wakeCheck)
    }
    @Test fun inactiveOneShotDuplicateUsesNearestNewTimeNotTomorrow() {
        val copy=AlarmShortcuts.copyForNext(AlarmPlan("a",hour=6,minute=30,enabled=false),"b",Instant.parse("2026-11-08T06:35:00Z"),ZoneOffset.UTC,null)
        assertEquals(Instant.parse("2026-11-08T06:40:00Z").toEpochMilli(),copy.oneShotAt)
    }
    @Test fun pendingOneShotDuplicateKeepsFutureDate() {
        val next=Instant.parse("2026-11-09T23:55:00Z")
        val copy=AlarmShortcuts.copyForNext(AlarmPlan("a",hour=23,minute=55),"b",Instant.parse("2026-11-08T00:00:00Z"),ZoneOffset.UTC,next.toEpochMilli())
        assertEquals(Instant.parse("2026-11-10T00:05:00Z").toEpochMilli(),copy.oneShotAt)
    }

}

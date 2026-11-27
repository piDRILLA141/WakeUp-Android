package ru.podyom.core

import org.junit.Assert.*
import org.junit.Test

class MissionPracticeTest {
    @Test fun usesTheConfiguredDifficultyAndRepetitionWithAnIndependentClock() {
        var time = 100L
        val spec = MissionSpec(MissionKind.MATH, count = 2, difficulty = 3)
        val attempt = MissionPractice(spec, 24) { time }
        assertFalse(attempt.answer(null, "wrong"))
        assertTrue(attempt.answer(null, Missions.math(24, 3).answer.toString()))
        assertEquals(1, attempt.progress)
        time = 2467
        assertTrue(attempt.answer(null, Missions.math(25, 3).answer.toString()))
        assertEquals(2367L, attempt.resultMillis as Long)
        val retry = MissionPractice(spec, 25) { time }
        assertEquals(0, retry.progress)
        assertNull(retry.resultMillis)
    }

    @Test fun handlesAllSensorTextMemoryAndCameraResultTypes() {
        val profile = PrivateAlarm(phrase = "Новое утро", qr = "fixture-code")
        val cases = mapOf(
            MissionKind.TEXT to { p: MissionPractice -> p.answer(profile, "Новое утро") },
            MissionKind.MEMORY to { p: MissionPractice -> p.answer(null, Missions.memory(p.roundSeed, 3, 4).joinToString(",")) },
            MissionKind.QR to { p: MissionPractice -> p.answer(profile, "fixture-code") },
        )
        cases.forEach { (kind, submit) ->
            val practice = MissionPractice(MissionSpec(kind, count = 1), 6) { 42 }
            assertTrue(submit(practice))
            assertNotNull(practice.resultMillis)
        }
        for (kind in listOf(MissionKind.STEPS, MissionKind.SHAKE)) {
            val practice = MissionPractice(MissionSpec(kind, count = 3), 6) { 42 }
            practice.matchedPhoto()
            assertEquals(0, practice.progress)
            practice.movement(12)
            assertEquals(3, practice.progress)
        }
        val photo = MissionPractice(MissionSpec(MissionKind.PHOTO, count = 1), 6) { 42 }
        photo.movement(1)
        assertFalse(photo.answer(profile, "matching"))
        assertNull(photo.resultMillis)
        photo.matchedPhoto()
        assertNotNull(photo.resultMillis)
    }
}

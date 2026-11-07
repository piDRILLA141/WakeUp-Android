package ru.podyom.core

import java.text.Normalizer
import kotlin.math.sqrt
import kotlin.random.Random

data class MathChallenge(val text: String, val answer: Int)
object Missions {
    fun math(seed: Int, difficulty: Int): MathChallenge {
        val random = Random(seed)
        val bound = when(difficulty) { 1 -> 10; 2 -> 30; else -> 100 }
        val a = random.nextInt(1, bound + 1); val b = random.nextInt(1, bound + 1)
        return when(random.nextInt(if (difficulty == 3) 5 else 4)) {
            0 -> MathChallenge("$a + $b", a + b)
            1 -> MathChallenge("${a + b} − $b", a)
            2 -> MathChallenge("$a × $b", a * b)
            3 -> MathChallenge("${a * b} ÷ $b", a)
            else -> { val c = random.nextInt(1, 10); MathChallenge("($a + $b) × $c", (a + b) * c) }
        }
    }
    fun challengeSeed(r: AlarmRun) = r.seed + r.cycle * 100003 + r.missionIndex * 1009 + r.progress * 31
    fun memory(seed: Int, grid: Int, length: Int) = Random(seed).let { rng -> List(length) { rng.nextInt(grid * grid) } }
    fun textMatches(expected: String, actual: String): Boolean =
        Normalizer.normalize(expected.trim(), Normalizer.Form.NFC) == Normalizer.normalize(actual.trim(), Normalizer.Form.NFC)
    fun qrMatches(expected: String, actual: String) = expected.isNotEmpty() && expected == actual
    fun available(kind: MissionKind, hasSensor: Boolean) = kind !in setOf(MissionKind.SHAKE, MissionKind.STEPS) || hasSensor
}
/** Hysteresis + refractory interval: one gentle motion cannot produce many counts. */
class ShakeDetector {
    private var armed = true
    private var last = -1000L
    fun sample(x: Float, y: Float, z: Float, millis: Long): Boolean {
        val g = sqrt(x*x+y*y+z*z) / 9.80665f
        if (g < 1.25f) armed = true
        if (g > 1.8f && armed && millis - last >= 650) { armed = false; last = millis; return true }
        return false
    }
}
class StepCounter {
    private var baseline: Int? = null
    fun sample(total: Int): Int {
        val previous = baseline
        baseline = total
        return if (previous == null || total < previous) 0 else (total - previous).coerceAtMost(20)
    }
}

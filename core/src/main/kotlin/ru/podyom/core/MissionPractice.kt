package ru.podyom.core

/** A practice attempt owns only in-memory values and never touches alarm state. */
class MissionPractice(val spec: MissionSpec, val seed: Int, private val clock: () -> Long) {
    private val started = clock()
    var progress: Int = 0
        private set
    var resultMillis: Long? = null
        private set

    val roundSeed: Int get() = seed + progress

    fun answer(profile: PrivateAlarm?, value: String): Boolean {
        if (resultMillis != null || !Missions.answerMatches(spec, roundSeed, profile, value)) return false
        advance(1)
        return true
    }

    fun movement(count: Int) {
        if (spec.kind in setOf(MissionKind.SHAKE, MissionKind.STEPS)) advance(count)
    }

    fun matchedPhoto() {
        if (spec.kind == MissionKind.PHOTO) advance(1)
    }

    private fun advance(count: Int) {
        if (resultMillis != null || count <= 0) return
        progress = (progress + count.coerceAtMost(spec.count)).coerceAtMost(spec.count)
        if (progress == spec.count) resultMillis = (clock() - started).coerceAtLeast(0)
    }
}

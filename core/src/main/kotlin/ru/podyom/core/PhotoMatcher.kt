package ru.podyom.core

import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Bounded, offline feature extraction. Harris corners on a pyramid supply points,
 * a rotated binary patch descriptor supplies appearance, and affine consensus
 * checks that candidate correspondences describe one scene.
 */
object PhotoMatcher {
    const val MAX_SIDE = 448
    private const val MIN_SIDE = 64
    private const val PATCH_RADIUS = 16
    private const val POINTS_PER_LEVEL = 144
    private const val MIN_MATCHES = 14

    data class Frame(val width: Int, val height: Int, val luma: ByteArray) {
        init {
            require(width in MIN_SIDE..MAX_SIDE && height in MIN_SIDE..MAX_SIDE &&
                luma.size == width * height)
        }

        fun at(x: Int, y: Int) = luma[y * width + x].toInt() and 255
    }

    @Serializable
    data class Feature(val x: Float, val y: Float, val words: List<Long>)

    @Serializable
    data class Template(val width: Int, val height: Int, val points: List<Feature>, val version: Int = 1) {
        fun usable(): Boolean = version == 1 && width in MIN_SIDE..MAX_SIDE && height in MIN_SIDE..MAX_SIDE &&
            points.size in MIN_MATCHES..POINTS_PER_LEVEL * 4 &&
            points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..width.toFloat() &&
                it.y in 0f..height.toFloat() && it.words.size == 4 }
    }

    enum class Problem { LIGHT, DETAILS, DIFFERENT, INVALID }

    data class Capture(val template: Template?, val problem: Problem? = null)
    data class Match(val accepted: Boolean, val inliers: Int = 0, val problem: Problem? = null)

    fun extract(frame: Frame, cancelled: () -> Boolean = { false }): Capture {
        checkCancelled(cancelled)
        var mean = 0.0
        var useful = 0
        for (value in frame.luma) {
            val intensity = value.toInt() and 255
            mean += intensity
            if (intensity in 34..220) useful++
        }
        mean /= frame.luma.size
        if (mean !in 30.0..230.0 || useful < frame.luma.size / 14) return Capture(null, Problem.LIGHT)

        val points = ArrayList<Feature>()
        for (scale in doubleArrayOf(1.0, 1.23, 1.51, 1.86)) {
            checkCancelled(cancelled)
            val level = smooth(resize(frame, scale))
            val corners = findCorners(level, cancelled)
            corners.forEach { corner ->
                points += Feature(
                    (corner.x * frame.width.toDouble() / level.width).toFloat(),
                    (corner.y * frame.height.toDouble() / level.height).toFloat(),
                    describe(level, corner),
                )
            }
        }
        if (points.size < 26 || coverage(points, frame.width, frame.height) < 4) return Capture(null, Problem.DETAILS)
        return Capture(Template(frame.width, frame.height, points))
    }

    fun compare(reference: Template, sample: Template, cancelled: () -> Boolean = { false }): Match {
        if (!reference.usable() || !sample.usable()) return Match(false, problem = Problem.INVALID)
        val backwards = IntArray(sample.points.size) { -1 }
        val backDistance = IntArray(sample.points.size) { Int.MAX_VALUE }
        val forwards = IntArray(reference.points.size) { -1 }

        reference.points.forEachIndexed { index, point ->
            checkCancelled(cancelled)
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestIndex = -1
            sample.points.forEachIndexed { candidateIndex, candidate ->
                var distance = 0
                for (word in 0..3) distance += (point.words[word] xor candidate.words[word]).countOneBits()
                if (distance < backDistance[candidateIndex]) {
                    backwards[candidateIndex] = index
                    backDistance[candidateIndex] = distance
                }
                if (distance < best) {
                    second = best
                    best = distance
                    bestIndex = candidateIndex
                } else if (distance < second) second = distance
            }
            if (best <= 72 && best * 100 < second * 81) forwards[index] = bestIndex
        }
        val matches = forwards.indices.mapNotNull { index ->
            val other = forwards[index]
            if (other >= 0 && backwards[other] == index) Pairing(reference.points[index], sample.points[other],
                backDistance[other]) else null
        }.sortedBy { it.distance }
        if (matches.size < MIN_MATCHES) return Match(false, problem = Problem.DIFFERENT)

        // Fixed seed gives reproducible outcomes. Bias the initial draws towards the most distinctive matches.
        val random = java.util.Random(2089)
        var best = emptyList<Pairing>()
        for (iteration in 0 until 320) {
            checkCancelled(cancelled)
            val pool = if (iteration < 100) min(matches.size, 32) else matches.size
            val i = random.nextInt(pool)
            val j = random.nextInt(pool)
            val k = random.nextInt(pool)
            if (i == j || j == k || i == k) continue
            val model = Affine.fit(matches[i], matches[j], matches[k]) ?: continue
            val inliers = matches.filter { model.fits(it) }
            if (inliers.size > best.size) best = inliers
        }
        val spreadA = coverage(best.map { it.from }, reference.width, reference.height)
        val spreadB = coverage(best.map { it.to }, sample.width, sample.height)
        val enough = best.size >= MIN_MATCHES && best.size * 100 >= matches.size * 43 &&
            spreadA >= 4 && spreadB >= 4
        return Match(enough, best.size, if (enough) null else Problem.DIFFERENT)
    }

    private fun resize(frame: Frame, scale: Double): Frame {
        if (scale == 1.0) return frame
        val width = (frame.width / scale).roundToInt().coerceAtLeast(MIN_SIDE)
        val height = (frame.height / scale).roundToInt().coerceAtLeast(MIN_SIDE)
        val values = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            values[y * width + x] = sample(frame, x * frame.width.toDouble() / width,
                y * frame.height.toDouble() / height).roundToInt().toByte()
        }
        return Frame(width, height, values)
    }

    private fun smooth(frame: Frame): Frame {
        val result = frame.luma.copyOf()
        for (y in 1 until frame.height - 1) for (x in 1 until frame.width - 1) {
            val axial = frame.at(x - 1, y) + frame.at(x + 1, y) + frame.at(x, y - 1) + frame.at(x, y + 1)
            val diagonal = frame.at(x - 1, y - 1) + frame.at(x + 1, y - 1) +
                frame.at(x - 1, y + 1) + frame.at(x + 1, y + 1)
            result[y * frame.width + x] = ((4 * frame.at(x, y) + 2 * axial + diagonal) / 16).toByte()
        }
        return Frame(frame.width, frame.height, result)
    }

    private data class Corner(val x: Int, val y: Int, val score: Float)

    private fun findCorners(frame: Frame, cancelled: () -> Boolean): List<Corner> {
        val width = frame.width
        val height = frame.height
        val response = FloatArray(width * height)
        for (y in PATCH_RADIUS until height - PATCH_RADIUS) {
            if (y % 16 == 0) checkCancelled(cancelled)
            for (x in PATCH_RADIUS until width - PATCH_RADIUS) {
                var xx = 0
                var yy = 0
                var xy = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val gx = frame.at(x + dx + 1, y + dy) - frame.at(x + dx - 1, y + dy)
                    val gy = frame.at(x + dx, y + dy + 1) - frame.at(x + dx, y + dy - 1)
                    xx += gx * gx
                    yy += gy * gy
                    xy += gx * gy
                }
                val trace = xx.toFloat() + yy
                val score = xx.toFloat() * yy - xy.toFloat() * xy - 0.045f * trace * trace
                if (score >= 2_200_000f) response[y * width + x] = score
            }
        }
        val candidates = ArrayList<Corner>()
        for (y in PATCH_RADIUS until height - PATCH_RADIUS) for (x in PATCH_RADIUS until width - PATCH_RADIUS) {
            val score = response[y * width + x]
            if (score == 0f) continue
            var maximum = true
            for (dy in -2..2) for (dx in -2..2) {
                val neighbour = response[(y + dy) * width + x + dx]
                if (neighbour > score || (neighbour == score && (dy < 0 || dy == 0 && dx < 0))) maximum = false
            }
            if (maximum) candidates += Corner(x, y, score)
        }
        val slots = IntArray(36)
        val selected = ArrayList<Corner>()
        for (corner in candidates.sortedByDescending { it.score }) {
            val cell = 6 * (6 * corner.y / height) + (6 * corner.x / width)
            if (slots[cell] == 5 || selected.any { abs(it.x - corner.x) < 5 && abs(it.y - corner.y) < 5 }) continue
            slots[cell]++
            selected += corner
            if (selected.size == POINTS_PER_LEVEL) break
        }
        return selected
    }

    private fun describe(frame: Frame, point: Corner): List<Long> {
        var horizontal = 0
        var vertical = 0
        for (dy in -10..10) for (dx in -10..10) {
            if (dx * dx + dy * dy > 100) continue
            val intensity = frame.at(point.x + dx, point.y + dy)
            horizontal += dx * intensity
            vertical += dy * intensity
        }
        val angle = atan2(vertical.toDouble(), horizontal.toDouble())
        val sin = sin(angle)
        val cos = cos(angle)
        val words = LongArray(4)
        for (index in pattern.indices) {
            val pair = pattern[index]
            fun read(dx: Int, dy: Int): Double = sample(frame, point.x + cos * dx - sin * dy, point.y + sin * dx + cos * dy)
            if (read(pair[0], pair[1]) < read(pair[2], pair[3])) {
                words[index / 64] = words[index / 64] or (1L shl (index % 64))
            }
        }
        return words.toList()
    }

    private val pattern = java.util.Random(605).let { random ->
        fun offset(): Pair<Int, Int> {
            while (true) {
                val x = random.nextInt(25) - 12
                val y = random.nextInt(25) - 12
                if (x * x + y * y <= 144) return x to y
            }
        }
        Array(256) {
            val (x, y) = offset()
            var other = offset()
            while (abs(x - other.first) + abs(y - other.second) < 4) other = offset()
            intArrayOf(x, y, other.first, other.second)
        }
    }

    private fun sample(frame: Frame, x: Double, y: Double): Double {
        val left = x.toInt().coerceIn(0, frame.width - 2)
        val top = y.toInt().coerceIn(0, frame.height - 2)
        val dx = (x - left).coerceIn(0.0, 1.0)
        val dy = (y - top).coerceIn(0.0, 1.0)
        return (1 - dy) * ((1 - dx) * frame.at(left, top) + dx * frame.at(left + 1, top)) +
            dy * ((1 - dx) * frame.at(left, top + 1) + dx * frame.at(left + 1, top + 1))
    }

    private fun coverage(points: List<Feature>, width: Int, height: Int): Int {
        val occupied = BooleanArray(25)
        points.forEach {
            val column = (5 * it.x / width).toInt().coerceIn(0, 4)
            val row = (5 * it.y / height).toInt().coerceIn(0, 4)
            occupied[row * 5 + column] = true
        }
        return occupied.count { it }
    }

    private data class Pairing(val from: Feature, val to: Feature, val distance: Int)

    private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val x: Double, val y: Double) {
        fun fits(pair: Pairing): Boolean {
            val dx = a * pair.from.x + b * pair.from.y + x - pair.to.x
            val dy = c * pair.from.x + d * pair.from.y + y - pair.to.y
            return dx * dx + dy * dy <= 5.5 * 5.5
        }

        companion object {
            fun fit(one: Pairing, two: Pairing, three: Pairing): Affine? {
                val x1 = (two.from.x - one.from.x).toDouble()
                val y1 = (two.from.y - one.from.y).toDouble()
                val x2 = (three.from.x - one.from.x).toDouble()
                val y2 = (three.from.y - one.from.y).toDouble()
                val determinant = x1 * y2 - x2 * y1
                if (abs(determinant) < 400) return null
                val u1 = two.to.x - one.to.x
                val v1 = two.to.y - one.to.y
                val u2 = three.to.x - one.to.x
                val v2 = three.to.y - one.to.y
                val a = (u1 * y2 - u2 * y1) / determinant
                val b = (u2 * x1 - u1 * x2) / determinant
                val c = (v1 * y2 - v2 * y1) / determinant
                val d = (v2 * x1 - v1 * x2) / determinant
                val scaleX = hypot(a, c)
                val scaleY = hypot(b, d)
                if (a * d - b * c <= 0 || scaleX !in .58..1.72 || scaleY !in .58..1.72 ||
                    abs(a * b + c * d) > .28 * scaleX * scaleY || scaleX / scaleY !in .7..1.43) return null
                return Affine(a, b, c, d, one.to.x - a * one.from.x - b * one.from.y,
                    one.to.y - c * one.from.x - d * one.from.y)
            }
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Photo analysis cancelled")
    }
}

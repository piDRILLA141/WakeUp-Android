package ru.podyom.core

import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PhotoMatcherTest {
    private val size = 320
    private fun scene(seed: Long): PhotoMatcher.Frame {
        val random = java.util.Random(seed)
        val pixels = ByteArray(size * size) { 118 }
        repeat(130) {
            val x = random.nextInt(size - 28) + 4
            val y = random.nextInt(size - 28) + 4
            val width = random.nextInt(23) + 3
            val height = random.nextInt(19) + 3
            val value = (random.nextInt(190) + 29).toByte()
            for (row in y until y + height) for (col in x until x + width) {
                if (it % 3 != 0 || (col - x) * height + (row - y) * width < width * height) {
                    pixels[row * size + col] = value
                }
            }
        }
        return PhotoMatcher.Frame(size, size, pixels)
    }

    private fun transform(source: PhotoMatcher.Frame, angle: Double, scale: Double, shear: Double, light: Double): PhotoMatcher.Frame {
        val output = ByteArray(size * size)
        val sin = sin(angle)
        val cos = cos(angle)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x - 164) / scale
            val dy = (y - 153) / scale
            val sx = 160 + dx * cos + dy * sin - shear * dy
            val sy = 160 - dx * sin + dy * cos
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            val value = if (left in 0 until size - 1 && top in 0 until size - 1) {
                val fx = sx - left
                val fy = sy - top
                (source.at(left, top) * (1 - fx) + source.at(left + 1, top) * fx) * (1 - fy) +
                    (source.at(left, top + 1) * (1 - fx) + source.at(left + 1, top + 1) * fx) * fy
            } else 118.0
            output[y * size + x] = (light * value + 16).roundToInt().coerceIn(0, 255).toByte()
        }
        return PhotoMatcher.Frame(size, size, output)
    }

    @Test fun acceptsReferenceAndModerateGeometricAndLightChangesAndRejectsUnrelatedFixtures() {
        val first = scene(1)
        val reference = requireNotNull(PhotoMatcher.extract(first).template)
        val cases = listOf(
            transform(first, .15, 1.0, .0, .82),
            transform(first, -.18, 1.21, .021, 1.04),
            transform(first, .08, .84, .06, .9),
        )
        assertTrue(PhotoMatcher.compare(reference, reference).accepted)
        cases.forEachIndexed { index, sample ->
            val observed = PhotoMatcher.compare(reference, requireNotNull(PhotoMatcher.extract(sample).template))
            println("Photo fixture $index: ${observed.inliers} geometric inliers")
            assertTrue("Positive synthetic fixture $index: $observed", observed.accepted)
        }
        for (seed in 2L..7L) {
            val foreign = requireNotNull(PhotoMatcher.extract(scene(seed)).template)
            assertFalse("Unrelated synthetic fixture $seed", PhotoMatcher.compare(reference, foreign).accepted)
        }
    }

    @Test fun checksLightingBlurDamageAndCancellation() {
        val good = scene(3)
        val blurred = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0
            var count = 0
            for (dy in -14..14 step 4) for (dx in -14..14 step 4) {
                sum += good.at((x + dx).coerceIn(0, size - 1), (y + dy).coerceIn(0, size - 1))
                count++
            }
            blurred[y * size + x] = (sum / count).toByte()
        }
        val reference = requireNotNull(PhotoMatcher.extract(good).template)
        val blurredCapture = PhotoMatcher.extract(PhotoMatcher.Frame(size, size, blurred))
        assertTrue(blurredCapture.template?.let { !PhotoMatcher.compare(reference, it).accepted } ?: true)
        assertEquals(PhotoMatcher.Problem.LIGHT, PhotoMatcher.extract(PhotoMatcher.Frame(size, size, ByteArray(size * size))).problem)
        assertEquals(PhotoMatcher.Problem.DETAILS, PhotoMatcher.extract(PhotoMatcher.Frame(size, size, ByteArray(size * size) { 128.toByte() })).problem)
        assertEquals(PhotoMatcher.Problem.INVALID, PhotoMatcher.compare(reference.copy(version = 2), reference).problem)
        var checks = 0
        assertThrows(CancellationException::class.java) { PhotoMatcher.extract(good) { ++checks > 3 } }
        assertThrows(CancellationException::class.java) { PhotoMatcher.compare(reference, reference) { true } }
    }
}

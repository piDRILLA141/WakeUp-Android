package ru.podyom.core

import java.io.File
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
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
        val allocationProbe = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()
        fun allocated() = runCatching { allocationProbe?.getThreadAllocatedBytes(thread)?.takeIf { it >= 0 } }.getOrNull()
        val baseline = allocated()
        val start = System.nanoTime()
        val reference = requireNotNull(PhotoMatcher.extract(first).template)
        val duration = (System.nanoTime() - start) / 1_000_000
        val bytes = baseline?.let { before -> allocated()?.minus(before) }
        println("Photo metrics: first extraction $duration ms, ${reference.points.size} points, ${first.luma.size} luma bytes")
        if (bytes != null) println("Photo metrics: $bytes JVM bytes allocated during first extraction, cumulative allocation, not peak heap")
        val cases = listOf(
            transform(first, .15, 1.0, .0, .82),
            transform(first, -.18, 1.21, .021, 1.04),
            transform(first, .08, .84, .06, .9),
        )
        assertTrue(PhotoMatcher.compare(reference, reference).accepted)
        cases.forEachIndexed { index, sample ->
            val prepared = requireNotNull(PhotoMatcher.extract(sample).template)
            val compareStart = System.nanoTime()
            val observed = PhotoMatcher.compare(reference, prepared)
            println("Photo metrics: positive $index, ${observed.inliers} geometric inliers, ${(System.nanoTime() - compareStart) / 1_000_000} ms comparison")
            assertTrue("Positive synthetic fixture $index: $observed", observed.accepted)
        }
        for (seed in 2L..7L) {
            val foreign = requireNotNull(PhotoMatcher.extract(scene(seed)).template)
            assertFalse("Unrelated synthetic fixture $seed", PhotoMatcher.compare(reference, foreign).accepted)
        }
    }

    @Test fun transformedRealCoffeePhotoMatchesButDifferentPhotographDoesNot() {
        val folder = System.getenv("PODYOM_PHOTO_FIXTURES")?.let(::File)
        assumeTrue("CI provides the pinned CC0 photos", folder?.isDirectory == true)
        fun image(name: String): PhotoMatcher.Frame {
            val source = ImageIO.read(File(requireNotNull(folder), name))
            val shortSide = min(source.width, source.height)
            val left = (source.width - shortSide) / 2
            val top = (source.height - shortSide) / 2
            val pixels = ByteArray(size * size) { position ->
                val color = source.getRGB(left + position % size * shortSide / size, top + position / size * shortSide / size)
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                ((54 * red + 183 * green + 19 * blue) shr 8).toByte()
            }
            return PhotoMatcher.Frame(size, size, pixels)
        }
        val coffee = image("coffee.png")
        val template = requireNotNull(PhotoMatcher.extract(coffee).template)
        val sample = transform(coffee, .089, 1.09, .025, .89)
        val genuine = PhotoMatcher.compare(template, requireNotNull(PhotoMatcher.extract(sample).template))
        val foreign = PhotoMatcher.compare(template, requireNotNull(PhotoMatcher.extract(image("brick.png")).template))
        println("Photo metrics: CC0 coffee, ${genuine.inliers} geometric inliers after transform")
        println("Photo metrics: CC0 brick negative, ${foreign.inliers} geometric inliers")
        assertTrue("Transformed photo of the same scene: $genuine", genuine.accepted)
        assertFalse("Different CC0 photo: $foreign", foreign.accepted)
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

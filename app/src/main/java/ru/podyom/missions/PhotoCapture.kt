package ru.podyom.missions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import ru.podyom.core.PhotoMatcher
import ru.podyom.data.PhotoReference

internal sealed interface PhotoOutcome {
    data class Reference(val value: PhotoReference) : PhotoOutcome
    data class Compared(val result: PhotoMatcher.Match) : PhotoOutcome
    data class Quality(val problem: PhotoMatcher.Problem) : PhotoOutcome
    data object Unreadable : PhotoOutcome
}

internal inline fun <F : AutoCloseable, T> processAndClose(frame: F, read: (F) -> T): T =
    try { read(frame) } finally { frame.close() }

internal fun processPhoto(frame: PhotoMatcher.Frame, reference: PhotoReference?, cancelled: () -> Boolean): PhotoOutcome {
    val capture = PhotoMatcher.extract(frame, cancelled)
    val features = capture.template ?: return PhotoOutcome.Quality(requireNotNull(capture.problem))
    return if (reference != null) PhotoOutcome.Compared(PhotoMatcher.compare(reference.features, features, cancelled))
    else PhotoOutcome.Reference(PhotoReference(features, thumbnail(frame)))
}

/** Read only CameraX's Y plane. The allocated byte array is bounded even on large sensors. */
internal fun ImageProxy.copyLuma(): PhotoMatcher.Frame {
    val crop = cropRect
    val frameWidth = crop.width()
    val frameHeight = crop.height()
    val plane = planes[0]
    val source = plane.buffer
    val origin = source.position()
    val divisor = maxOf(frameWidth, frameHeight).toDouble() / PhotoMatcher.MAX_SIDE
    val w = (frameWidth / divisor.coerceAtLeast(1.0)).toInt()
    val h = (frameHeight / divisor.coerceAtLeast(1.0)).toInt()
    require(w >= 64 && h >= 64)
    val rotation = imageInfo.rotationDegrees
    val width = if (rotation == 90 || rotation == 270) h else w
    val height = if (rotation == 90 || rotation == 270) w else h
    val pixels = ByteArray(width * height)
    for (y in 0 until h) {
        val sensorY = crop.top + (y * frameHeight / h)
        for (x in 0 until w) {
            val sensorX = crop.left + (x * frameWidth / w)
            val index = when (rotation) {
                90 -> x * width + (h - 1 - y)
                180 -> (h - 1 - y) * width + (w - 1 - x)
                270 -> (w - 1 - x) * width + y
                else -> y * width + x
            }
            pixels[index] = source.get(origin + sensorY * plane.rowStride + sensorX * plane.pixelStride)
        }
    }
    return PhotoMatcher.Frame(width, height, pixels)
}

private fun thumbnail(frame: PhotoMatcher.Frame): String? {
    val scale = maxOf(frame.width, frame.height).toDouble() / 96
    val width = (frame.width / scale).toInt().coerceAtLeast(1)
    val height = (frame.height / scale).toInt().coerceAtLeast(1)
    val colors = IntArray(width * height) { index ->
        val luma = frame.at((index % width) * frame.width / width, (index / width) * frame.height / height)
        (255 shl 24) or (luma shl 16) or (luma shl 8) or luma
    }
    val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    return try {
        val buffer = ByteArrayOutputStream()
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, 72, buffer)) {
            Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP).takeIf { it.length <= 24_000 }
        } else null
    } finally { bitmap.recycle() }
}

internal fun decodePhotoThumbnail(value: String?): Bitmap? {
    if (value == null || value.length > 24_000) return null
    return try {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth in 1..96 && bounds.outHeight in 1..96) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
    } catch (_: IllegalArgumentException) { null }
}

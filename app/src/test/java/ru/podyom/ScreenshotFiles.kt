package ru.podyom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File

internal fun saveScreenshot(view: View, name: String) {
    require(view.width > 0 && view.height > 0)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        view.draw(Canvas(bitmap))
        val colors = HashSet<Int>()
        for (i in 0..24) for (j in 0..24) {
            colors += bitmap.getPixel(i * (view.width - 1) / 24, j * (view.height - 1) / 24)
        }
        check(colors.size > 2) { "Empty screenshot: $name" }
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally { bitmap.recycle() }
}

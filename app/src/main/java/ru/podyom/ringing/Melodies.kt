package ru.podyom.ringing

import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.core.net.toUri
import ru.podyom.R
import ru.podyom.core.PrivateAlarm

object Melodies {
    enum class Category(val title: String) {
        SOFT("Мягкие"), CLASSIC("Классические"), ENERGETIC("Энергичные"),
        INSISTENT("Настойчивые"), CUSTOM("Пользовательские")
    }

    data class Tone(val id: String, val name: String, val category: Category, @RawRes val resource: Int)

    val bundled = listOf(
        Tone("dawn", "Рассвет", Category.SOFT, R.raw.dawn),
        Tone("bell", "Утренний звон", Category.CLASSIC, R.raw.morning_bell),
        Tone("pulse", "Пульс", Category.ENERGETIC, R.raw.morning_pulse),
        Tone("signal", "Сигнал", Category.INSISTENT, R.raw.morning_signal),
    )
    val default get() = bundled.first()
    val escalationDefault get() = bundled.last()

    fun tone(id: String) = bundled.firstOrNull { it.id == id }
    fun uri(context: Context, tone: Tone): Uri = "android.resource://${context.packageName}/${tone.resource}".toUri()

    fun source(context: Context, profile: PrivateAlarm): Uri =
        profile.soundUri.takeIf(String::isNotBlank)?.let(Uri::parse) ?: uri(context, tone(profile.melodyId) ?: default)
}

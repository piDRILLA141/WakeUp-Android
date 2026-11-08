package ru.podyom.core

import java.time.LocalTime
import java.util.Locale

object SmartTime {
    fun parse(raw: String): LocalTime {
        val text=raw.trim()
        val parts=if (Regex("[0-9]{3,4}").matches(text)) listOf(text.dropLast(2),text.takeLast(2))
            else if(Regex("[0-9]{1,2}:[0-9]{2}").matches(text)) text.split(':')
            else throw IllegalArgumentException("Введите 3–4 цифры или время ЧЧ:ММ")
        val hour=parts[0].toInt();val minute=parts[1].toInt()
        require(hour in 0..23 && minute in 0..59) { "Часы: 00–23, минуты: 00–59" }
        return LocalTime.of(hour,minute)
    }
    fun normalize(raw: String): String = parse(raw).let { String.format(Locale.ROOT,"%02d:%02d",it.hour,it.minute) }
    data class Presentation(val text:String, val originalToVisual:List<Int>,val visualToOriginal:List<Int>)
    /** Formatting only; the editable text and IME selection are never rewritten during typing. */
    fun present(raw:String):Presentation {
        if(!Regex("[0-9]{3,4}").matches(raw)) return Presentation(raw,(0..raw.length).toList(),(0..raw.length).toList())
        val split=raw.length-2;val prefix=if(raw.length==3) "0" else ""
        val display=prefix+raw.take(split)+":"+raw.drop(split)
        val forward=(0..raw.length).map { prefix.length+it+if(it>=split) 1 else 0 }
        val inverse=(0..display.length).map { pos -> (pos-prefix.length-if(pos>prefix.length+split) 1 else 0).coerceIn(0,raw.length) }
        return Presentation(display,forward,inverse)
    }
}

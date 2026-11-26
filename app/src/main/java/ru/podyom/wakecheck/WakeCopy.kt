package ru.podyom.wakecheck

fun wakeConfirmedText(cancelledBackups: Int): String =
    if (cancelledBackups > 0) "Пробуждение подтверждено. Отменено резервных сигналов на сегодня: $cancelledBackups."
    else "Пробуждение подтверждено"

package ru.podyom.missions

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import ru.podyom.core.*
import ru.podyom.data.PhotoReference
import ru.podyom.ringing.MissionPanel
import kotlin.random.Random

@Composable
internal fun PracticeMission(
    spec: MissionSpec, profile: PrivateAlarm, photo: PhotoReference?, onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.large) { PracticePanel(spec, profile, photo, onClose) }
    }
}

@Composable
internal fun PracticePanel(
    spec: MissionSpec, profile: PrivateAlarm, photo: PhotoReference?, onClose: () -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime, newSeed: () -> Int = { Random.nextInt() },
) {
    var attempt by remember(spec) { mutableStateOf(MissionPractice(spec, newSeed(), now)) }
    var revision by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun retry(type: MissionSpec = spec) {
        attempt = MissionPractice(type, newSeed(), now)
        revision++
        feedback = null
    }
    val duration = remember(attempt, revision) { attempt.resultMillis }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Пробное задание · ${attempt.spec.kind.title}", style = MaterialTheme.typography.headlineSmall)
        Text("Будильник и проверка пробуждения не запускаются.")
        key(attempt) {
            when {
                duration != null -> Text("Выполнено за ${(duration + 999) / 1000} с", style = MaterialTheme.typography.titleLarge)
                attempt.spec.kind == MissionKind.PHOTO -> PhotoMission(photo, {
                    attempt.matchedPhoto(); revision++
                }, { retry(MissionSpec(MissionKind.MATH, count = 3)) })
                else -> {
                    Text("Выполнено ${attempt.progress} из ${attempt.spec.count}")
                    MissionPanel(attempt.spec, attempt.roundSeed, profile, onAnswer = {
                        feedback = if (attempt.answer(profile, it)) null else "Не совпало. Попробуйте ещё раз."
                        revision++
                    }, onMovement = {
                        attempt.movement(it); revision++
                    }, fallback = { retry(MissionSpec(MissionKind.MATH, count = 3)) })
                }
            }
        }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { retry() }) { Text("Повторить") }
            TextButton(onClick = onClose) { Text("Закрыть пробу") }
        }
    }
}

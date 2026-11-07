package ru.podyom.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.podyom.app

@Composable fun Readiness() {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exact = remember(revision) { context.app.scheduler.allowed() }
    val full = remember(revision) { Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent() }
    val channel = remember(revision) { manager.getNotificationChannel(ru.podyom.alarm.AlarmNotifications.RING)?.importance != NotificationManager.IMPORTANCE_NONE && manager.getNotificationChannel(ru.podyom.alarm.AlarmNotifications.CHECK)?.importance != NotificationManager.IMPORTANCE_NONE }
    val battery = remember(revision) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val audio = context.getSystemService(AudioManager::class.java)
    val audible = remember(revision) { audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0 }
    fun open(action: String, data: Boolean = false) {
        val intent = Intent(action).apply { if (data) this.data = Uri.parse("package:${context.packageName}") }
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Проверка готовности будильника", style = MaterialTheme.typography.headlineSmall)
        ReadyRow("Уведомления", notifications && channel, "Нужны для звонка и проверки пробуждения") {
            if (Build.VERSION.SDK_INT >= 33 && !notifications) request.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        ReadyRow("Точные будильники", exact, "Системное расписание работает при закрытом приложении") { open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
        ReadyRow("Полноэкранный показ", full, "Android решает, открыть экран звонка или показать уведомление") { if(Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true) else open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        ReadyRow("Энергосбережение", battery, "Проверьте ограничения фоновой работы") { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        ReadyRow("Громкость будильника", audible, "Не беспокоить и Bluetooth требуют отдельной проверки") { open(Settings.ACTION_SOUND_SETTINGS) }
        OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("iQOO / OriginOS", style = MaterialTheme.typography.titleMedium)
            Text("Автозапуск и фоновые ограничения: статус не проверяется автоматически. В системных настройках приложения разрешите фоновую работу и автозапуск, если такие пункты доступны. Названия зависят от версии и региона.")
            TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Настройки приложения") }
        } }
        Text("После принудительной остановки или при выключенном телефоне будильник не работает. После установки обязательно выполните тест с заблокированным экраном.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ReadyRow(title: String, ready: Boolean, detail: String, onClick: () -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Доступно" else "Требует проверки", color = if(ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(detail, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClick) { Text("Открыть настройки") }
    } }
}

package ru.podyom.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import ru.podyom.*
import ru.podyom.core.*
import ru.podyom.data.*
import ru.podyom.ringing.RingingService
import java.util.UUID

@Serializable data class BackupAlarm(val plan: AlarmPlan, val profile: PrivateAlarm)
@Serializable data class Backup(val version: Int = 1, val alarms: List<BackupAlarm>, val settings: AppSettings = AppSettings())
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val alarms = app.runtime.dao().observeAlarms().map { list -> list.map { it.model() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rows = app.runtime.dao().observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runs = rows.map { list -> list.map { it.model() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profiles = app.privateDb.dao().profiles().map { rows -> rows.associate { it.id to codec.decodeFromString<PrivateAlarm>(it.json) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val settings = app.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings(readySeen = true))
    val sleep = app.privateDb.dao().sleep().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { if(e is CancellationException) throw e; messages.emit(e.message ?: "Не удалось выполнить действие") } } }
    fun restore() = action { app.engine.restore() }
    fun save(plan: AlarmPlan, profile: PrivateAlarm, done: () -> Unit) = action {
        app.engine.save(plan, profile)
        val next = app.runtime.dao().runs().map { it.model() }.firstOrNull { it.plan.id == plan.id && it.state == RunState.SCHEDULED }
        messages.emit(if (next == null) "Будильник сохранён и выключен" else "Будильник прозвонит через ${duration(next.eventAt - System.currentTimeMillis())}")
        done()
    }
    fun enabled(id: String, value: Boolean) = action { app.engine.enable(id, value) }
    fun delete(id: String) = action { app.engine.delete(id) }
    fun skip(id: String) = action { app.engine.skip(id) }
    fun duplicate(plan: AlarmPlan, profile: PrivateAlarm) = action { app.engine.save(plan.copy(id = UUID.randomUUID().toString(), enabled = false), profile.copy(title = profile.title + " · копия")) }
    fun test(fast: Boolean = false) = action {
        val p = AlarmPlan(UUID.randomUUID().toString(), missions = listOf(MissionSpec(count = 1)), wakeDelaySeconds = if (fast) 10 else 180, confirmSeconds = if(fast) 10 else 100)
        app.engine.save(p, PrivateAlarm(title = if (fast) "Тест проверки пробуждения" else "Тест будильника"), System.currentTimeMillis() + 10_000)
        messages.emit("Настоящий системный будильник установлен через 10 секунд")
    }
    fun light(value: Boolean) = action { app.settings.light(value) }
    fun clock24(value: Boolean) = action { app.settings.clock24(value) }
    fun seen() = action { app.settings.seen() }
    fun toggleSleep() = action {
        val dao = app.privateDb.dao(); val current = dao.currentSleep()
        dao.putSleep(current?.copy(ended = System.currentTimeMillis()) ?: SleepRow(System.currentTimeMillis()))
    }
    fun clearHistory() = action { app.engine.clearHistory(); app.privateDb.dao().clearSleep() }
    fun export(uri: Uri) = action {
        val entries = app.runtime.dao().alarms().map { row -> val p = row.model(); BackupAlarm(p, app.engine.profile(p.privateKey) ?: PrivateAlarm()) }
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(codec.encodeToString(Backup(alarms = entries, settings = app.settings.flow.first()))) } }
        messages.emit("Будильники экспортированы. Файл содержит ваши фразы и QR-коды")
    }
    fun import(uri: Uri) = action {
        val text = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 1_048_576) { "Файл слишком большой" }; output.write(buffer, 0, count) }
            output.toByteArray().decodeToString()
        } }
        val backup = codec.decodeFromString<Backup>(text)
        require(backup.version == 1 && backup.alarms.size <= 500) { "Неподдерживаемый формат" }
        backup.alarms.forEach { it.plan.validate(); require(it.profile.phrase.length in 1..500 && it.profile.title.length <= 100 && it.profile.qr.length <= 4096); require(it.plan.missions.none { m -> m.kind == MissionKind.QR } || it.profile.qr.isNotEmpty()) }
        backup.alarms.forEach { app.engine.save(it.plan.copy(id = UUID.randomUUID().toString(), enabled = false), it.profile.copy(soundUri = "")) }
        app.settings.light(backup.settings.light); app.settings.clock24(backup.settings.clock24)
        messages.emit("Импортировано ${backup.alarms.size}. Включите нужные будильники; мелодии выберите заново")
    }
}
fun duration(millis: Long): String {
    val minutes = ((millis.coerceAtLeast(0) + 59_999) / 60_000)
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

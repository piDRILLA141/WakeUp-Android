package ru.podyom.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import ru.podyom.core.SleepSettings

@Serializable
data class SleepRecord(val settings: SleepSettings = SleepSettings(), val revision: Long = 0, val notifiedAt: Long = 0)

private val Context.preferences by preferencesDataStore("settings")
@kotlinx.serialization.Serializable
data class AppSettings(val light: Boolean = false, val clock24: Boolean = true, val readySeen: Boolean = false, val enhancedCheck: Boolean = false,
    val deduplicate: Boolean = true, val wakeSessionsSeen: Boolean = false)
class SettingsStore(private val context: Context) {
    private val light = booleanPreferencesKey("light")
    private val clock24 = booleanPreferencesKey("clock24")
    private val enhanced = booleanPreferencesKey("enhancedCheck")
    private val seen = booleanPreferencesKey("readySeen")
    private val sleepKey = stringPreferencesKey("sleepReminder")
    val sleepFlow = context.preferences.data.map { readSleep(it).settings }

    private fun readSleep(prefs: Preferences): SleepRecord = prefs[sleepKey]?.let {
        try { codec.decodeFromString<SleepRecord>(it).also { record -> record.settings.validate() } }
        catch (_: IllegalArgumentException) { null }
    } ?: SleepRecord()

    suspend fun sleepRecord(): SleepRecord = readSleep(context.preferences.data.first())

    suspend fun changeSleep(settings: SleepSettings): SleepRecord {
        settings.validate()
        return readSleep(context.preferences.edit { prefs ->
            prefs[sleepKey] = codec.encodeToString(SleepRecord(settings, readSleep(prefs).revision + 1))
        })
    }

    suspend fun markSleepNotified(revision: Long, at: Long) {
        context.preferences.edit { prefs ->
            val record = readSleep(prefs)
            if (record.revision == revision) prefs[sleepKey] = codec.encodeToString(record.copy(notifiedAt = at))
        }
    }

    private val wakeSessionsSeen = booleanPreferencesKey("wakeSessionsSeen")
    private val deduplication = kotlinx.coroutines.flow.MutableStateFlow(deduplicateEnabled())
    private val runtimePrefs get() = context.createDeviceProtectedStorageContext().getSharedPreferences("presentation", Context.MODE_PRIVATE)
    fun deduplicateEnabled() = runtimePrefs.getBoolean("deduplicateWakeSessions", true)
    suspend fun deduplicate(value: Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(runtimePrefs.edit().putBoolean("deduplicateWakeSessions", value).commit()) { "Не удалось сохранить настройку" }
            deduplication.value = value
        }
    }
    suspend fun markWakeSessionsSeen() { context.preferences.edit { it[wakeSessionsSeen] = true } }
    val flow = kotlinx.coroutines.flow.combine(deduplication, context.preferences.data) { deduplicate, it ->
        AppSettings(it[light] ?: false, it[clock24] ?: true, it[seen] ?: false, it[enhanced] ?: false,
            deduplicate, it[wakeSessionsSeen] ?: false)
    }
    suspend fun light(value: Boolean) { context.preferences.edit { it[light] = value } }
    suspend fun clock24(value: Boolean) { context.preferences.edit { it[clock24] = value } }
    suspend fun enhanced(value:Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(runtimePrefs.edit().putBoolean("enhancedCheck", value).commit()) { "Не удалось сохранить настройку" }
        }
        context.preferences.edit {it[enhanced]=value}
    }
    suspend fun seen() { context.preferences.edit { it[seen] = true } }
}

fun enhancedChecks(context:Context)=context.createDeviceProtectedStorageContext().getSharedPreferences("presentation",Context.MODE_PRIVATE).getBoolean("enhancedCheck",false)

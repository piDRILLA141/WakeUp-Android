package ru.podyom.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("settings")
@kotlinx.serialization.Serializable
data class AppSettings(val light: Boolean = false, val clock24: Boolean = true, val readySeen: Boolean = false, val enhancedCheck: Boolean = false)
class SettingsStore(private val context: Context) {
    private val light = booleanPreferencesKey("light")
    private val clock24 = booleanPreferencesKey("clock24")
    private val enhanced = booleanPreferencesKey("enhancedCheck")
    private val seen = booleanPreferencesKey("readySeen")
    val flow = context.preferences.data.map { AppSettings(it[light] ?: false, it[clock24] ?: true, it[seen] ?: false, it[enhanced] ?: false) }
    suspend fun light(value: Boolean) { context.preferences.edit { it[light] = value } }
    suspend fun clock24(value: Boolean) { context.preferences.edit { it[clock24] = value } }
    suspend fun enhanced(value:Boolean) {
        context.createDeviceProtectedStorageContext().getSharedPreferences("presentation",Context.MODE_PRIVATE).edit().putBoolean("enhancedCheck",value).commit()
        context.preferences.edit {it[enhanced]=value}
    }
    suspend fun seen() { context.preferences.edit { it[seen] = true } }
}

fun enhancedChecks(context:Context)=context.createDeviceProtectedStorageContext().getSharedPreferences("presentation",Context.MODE_PRIVATE).getBoolean("enhancedCheck",false)

package ru.podyom.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("settings")
data class AppSettings(val light: Boolean = false, val clock24: Boolean = true, val readySeen: Boolean = false)
class SettingsStore(private val context: Context) {
    private val light = booleanPreferencesKey("light")
    private val clock24 = booleanPreferencesKey("clock24")
    private val seen = booleanPreferencesKey("readySeen")
    val flow = context.preferences.data.map { AppSettings(it[light] ?: false, it[clock24] ?: true, it[seen] ?: false) }
    suspend fun light(value: Boolean) { context.preferences.edit { it[light] = value } }
    suspend fun clock24(value: Boolean) { context.preferences.edit { it[clock24] = value } }
    suspend fun seen() { context.preferences.edit { it[seen] = true } }
}

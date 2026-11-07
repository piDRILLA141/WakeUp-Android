package ru.podyom

import android.app.Application
import android.content.Context
import android.os.UserManager
import ru.podyom.alarm.*
import ru.podyom.data.*

class App : Application() {
    val runtime by lazy { RuntimeDatabase.open(this) }
    val privateDb by lazy { check(unlocked); PrivateDatabase.open(this) }
    val settings by lazy { SettingsStore(this) }
    val scheduler by lazy { AlarmScheduler(this) }
    val engine by lazy { AlarmEngine(this) }
    val unlocked get() = getSystemService(UserManager::class.java).isUserUnlocked
    override fun onCreate() { super.onCreate(); AlarmNotifications.channels(this) }
}
val Context.app get() = applicationContext as App

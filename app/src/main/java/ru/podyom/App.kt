package ru.podyom

import android.app.Application
import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
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
    val foreground=MutableStateFlow(false)
    private val resumed=mutableSetOf<Activity>()
    override fun onCreate() {
        super.onCreate(); AlarmNotifications.channels(this)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:Activity) {resumed.add(activity);foreground.value=true}
            override fun onActivityPaused(activity:Activity) {resumed.remove(activity);foreground.value=resumed.isNotEmpty()}
            override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?) {}
            override fun onActivityStarted(activity:Activity) {}
            override fun onActivityStopped(activity:Activity) {}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
            override fun onActivityDestroyed(activity:Activity) {resumed.remove(activity);foreground.value=resumed.isNotEmpty()}
        })
    }
}
val Context.app get() = applicationContext as App

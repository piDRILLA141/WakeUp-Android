package ru.podyom

import android.app.Application
import android.app.Activity
import android.app.KeyguardManager
import android.os.PowerManager
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
    val photoReferences by lazy { PhotoReferences(this) }
    val scheduler by lazy { AlarmScheduler(this) }
    val journal by lazy { TechnicalJournal(this) }
    val foregroundScheduleRestored = java.util.concurrent.atomic.AtomicBoolean(false)
    val ringingForeground = MutableStateFlow(false)
    val engine by lazy { AlarmEngine(this) }
    val unlocked get() = getSystemService(UserManager::class.java).isUserUnlocked
    val foreground=MutableStateFlow(false)
    fun hasVisibleCheckHost(): Boolean = ringingForeground.value ||
        (foreground.value && getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    private val resumed=mutableSetOf<Activity>()
    private fun publishForeground() {
        ringingForeground.value = resumed.any { it is ru.podyom.ringing.RingingActivity }
        foreground.value = resumed.isNotEmpty()
    }
    override fun onCreate() {
        super.onCreate(); AlarmNotifications.channels(this)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:Activity) {resumed.add(activity);publishForeground()}
            override fun onActivityPaused(activity:Activity) {resumed.remove(activity);publishForeground()}
            override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?) {}
            override fun onActivityStarted(activity:Activity) {}
            override fun onActivityStopped(activity:Activity) {}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
            override fun onActivityDestroyed(activity:Activity) {resumed.remove(activity);publishForeground()}
        })
    }
}
val Context.app get() = applicationContext as App

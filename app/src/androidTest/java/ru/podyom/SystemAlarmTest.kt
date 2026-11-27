package ru.podyom

import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.podyom.alarm.AlarmScheduler
import ru.podyom.core.AlarmPlan
import ru.podyom.core.AlarmRun

@RunWith(AndroidJUnit4::class)
class SystemAlarmTest {
    @Test fun exactAlarmClockIsVisibleToAndroidAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AlarmScheduler(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        val trigger = System.currentTimeMillis() + 90_000L
        val run = AlarmRun("instrumentation-alarm", AlarmPlan("instrumentation-plan"), trigger)
        assertTrue(scheduler.allowed())
        try {
            scheduler.schedule(run)
            val registered = manager.nextAlarmClock
            assertEquals(trigger, registered?.triggerTime)
            assertEquals(context.packageName, registered?.showIntent?.creatorPackage)
        } finally {
            scheduler.cancel(run)
        }
        assertNotEquals(trigger, manager.nextAlarmClock?.triggerTime)
    }
}

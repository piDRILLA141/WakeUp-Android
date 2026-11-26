package ru.podyom

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.podyom.data.RuntimeDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),RuntimeDatabase::class.java)
    @Test fun versionTwoAlarmsAndChecksSurviveGroupMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val name = "group-migration-test"
        context.deleteDatabase(name)
        helper.createDatabase(context.getDatabasePath(name).absolutePath, 2).apply {
            execSQL("INSERT INTO alarms(id,json) VALUES ('alarm','{}')")
            execSQL("INSERT INTO runs(id,alarmId,state,eventAt,json) VALUES ('run','alarm','WAKE_CHECK_ACTIVE',12345,'{}')")
            close()
        }
        val room = androidx.room.Room.databaseBuilder(context, RuntimeDatabase::class.java, name)
            .addMigrations(RuntimeDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
                val db = room.openHelper.writableDatabase
                db.query("SELECT id FROM alarms").use { assertTrue(it.moveToFirst()); assertEquals("alarm", it.getString(0)) }
                db.query("SELECT state FROM runs").use { assertTrue(it.moveToFirst()); assertEquals("WAKE_CHECK_ACTIVE", it.getString(0)) }
                db.query("SELECT COUNT(*) FROM alarm_groups").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            } finally { room.close() }
    }

    @Test fun versionOneRunSurvivesUpgrade() {
        helper.createDatabase("migration-test",1).apply {
            execSQL("INSERT INTO runs(id,alarmId,state,eventAt,json) VALUES ('run','alarm','WAITING_WAKE_CHECK',12345,'{}')")
            close()
        }
        helper.runMigrationsAndValidate("migration-test",2,true,RuntimeDatabase.MIGRATION_1_2).use {db->
            db.query("SELECT state,eventAt,diagnostic FROM runs WHERE id='run'").use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals("WAITING_WAKE_CHECK",cursor.getString(0));assertEquals(12345L,cursor.getLong(1));assertEquals("",cursor.getString(2))
            }
        }
    }
}

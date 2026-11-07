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

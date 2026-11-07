package ru.podyom

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.podyom.data.RuntimeDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36], application=App::class)
class RuntimeMigrationTest {
    @Test fun migrationPreservesPendingCheckAndValidatesRoomSchema() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        val name="migration-real.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(1){
            override fun onCreate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS alarms (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS runs (id TEXT NOT NULL PRIMARY KEY, alarmId TEXT NOT NULL, state TEXT NOT NULL, eventAt INTEGER NOT NULL, json TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_runs_alarmId ON runs(alarmId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId TEXT NOT NULL, at INTEGER NOT NULL, type TEXT NOT NULL)")
                db.execSQL("INSERT INTO runs VALUES ('r','a','WAITING_WAKE_CHECK',12345,'{}')")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) {}
        }).build())
        helper.writableDatabase;helper.close()
        val migrated=Room.databaseBuilder(context,RuntimeDatabase::class.java,name).addMigrations(RuntimeDatabase.MIGRATION_1_2).build()
        try {val row=migrated.dao().run("r")!!;assertEquals("WAITING_WAKE_CHECK",row.state);assertEquals(12345L,row.eventAt);assertEquals("",row.diagnostic)} finally {migrated.close()}
    }
}

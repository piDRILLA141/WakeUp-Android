package ru.podyom.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import ru.podyom.core.*

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Entity(tableName = "alarms") data class AlarmRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "runs", indices = [Index("alarmId")])
data class RunRow(@PrimaryKey val id: String, val alarmId: String, val state: String, val eventAt: Long, val json: String,
    @ColumnInfo(defaultValue = "''") val diagnostic: String = "")
@Entity(tableName = "events") data class EventRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: String, val at: Long, val type: String)
@Entity(tableName = "alarm_groups")
data class GroupRow(@PrimaryKey val id: String, val json: String)
fun GroupRow.model() = codec.decodeFromString<AlarmGroup>(json)
fun AlarmGroup.row() = GroupRow(id, codec.encodeToString(this))

@Entity(tableName = "profiles") data class ProfileRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "sleep") data class SleepRow(@PrimaryKey val started: Long, val ended: Long = 0)
fun AlarmRow.model() = codec.decodeFromString<AlarmPlan>(json)
fun AlarmPlan.row() = AlarmRow(id, codec.encodeToString(this))
fun RunRow.model() = codec.decodeFromString<AlarmRun>(json)
fun AlarmRun.row() = RunRow(id, plan.id, state.name, eventAt, codec.encodeToString(this))

@Dao interface RuntimeDao {
    @Query("SELECT * FROM alarm_groups") fun observeGroups(): Flow<List<GroupRow>>
    @Query("SELECT * FROM alarm_groups WHERE id = :id") suspend fun group(id: String): GroupRow?
    @Upsert suspend fun putGroup(row: GroupRow)
    @Query("DELETE FROM alarm_groups WHERE id = :id") suspend fun deleteGroup(id: String)
    @Query("SELECT * FROM alarms") fun observeAlarms(): Flow<List<AlarmRow>>
    @Query("SELECT * FROM runs ORDER BY eventAt ASC") fun observeRuns(): Flow<List<RunRow>>
    @Query("SELECT * FROM events ORDER BY at DESC LIMIT 1000") fun observeEvents(): Flow<List<EventRow>>
    @Query("SELECT * FROM events WHERE type LIKE 'TECH|%' ORDER BY id DESC LIMIT 400") suspend fun technicalEvents(): List<EventRow>
    @Query("DELETE FROM events WHERE (type LIKE 'TECH|%' OR substr(type, 1, 6) = 'AUDIO_') AND id NOT IN (SELECT id FROM events WHERE type LIKE 'TECH|%' OR substr(type, 1, 6) = 'AUDIO_' ORDER BY id DESC LIMIT 400)")
    suspend fun trimTechnicalEvents()
    @Query("SELECT * FROM runs WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'MISSED')") fun observeLiveRuns(): Flow<List<RunRow>>
    @Query("SELECT * FROM runs WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'MISSED')") suspend fun liveRuns(): List<RunRow>
    @Query("SELECT * FROM alarms") suspend fun alarms(): List<AlarmRow>
    @Query("SELECT * FROM runs") suspend fun runs(): List<RunRow>
    @Query("SELECT * FROM runs WHERE state = 'COMPLETED' AND eventAt BETWEEN :from AND :through")
    suspend fun completedNear(from: Long, through: Long): List<RunRow>
    @Query("SELECT * FROM runs WHERE id = :id") suspend fun run(id: String): RunRow?
    @Upsert suspend fun putAlarm(row: AlarmRow)
    @Upsert suspend fun putRun(row: RunRow)
    @Insert suspend fun event(row: EventRow)
    @Query("DELETE FROM runs WHERE id = :id AND state = 'SCHEDULED'") suspend fun deletePendingRun(id: String)
    @Query("DELETE FROM events WHERE runId = :id AND type = 'SCHEDULED'") suspend fun deleteScheduledEvent(id: String)
    @Query("DELETE FROM alarms WHERE id = :id") suspend fun deleteAlarm(id: String)
    @Query("DELETE FROM runs WHERE state IN ('COMPLETED', 'CANCELLED', 'MISSED')") suspend fun clearHistory()
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("UPDATE runs SET diagnostic = :message WHERE id = :id") suspend fun diagnostic(id: String, message: String)
}
@Database(entities = [AlarmRow::class, RunRow::class, EventRow::class, GroupRow::class], version = 3, exportSchema = true)
abstract class RuntimeDatabase : RoomDatabase() {
    abstract fun dao(): RuntimeDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE runs ADD COLUMN diagnostic TEXT NOT NULL DEFAULT ''") }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `alarm_groups` (`id` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context.createDeviceProtectedStorageContext(), RuntimeDatabase::class.java, "runtime.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
@Dao interface PrivateDao {
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun profile(id: String): ProfileRow?
    @Query("SELECT * FROM profiles") fun profiles(): Flow<List<ProfileRow>>
    @Upsert suspend fun putProfile(row: ProfileRow)
    @Query("SELECT * FROM sleep ORDER BY started DESC") fun sleep(): Flow<List<SleepRow>>
    @Query("SELECT * FROM sleep WHERE ended = 0 LIMIT 1") suspend fun currentSleep(): SleepRow?
    @Upsert suspend fun putSleep(row: SleepRow)
    @Query("DELETE FROM sleep") suspend fun clearSleep()
}
@Database(entities = [ProfileRow::class, SleepRow::class], version = 1, exportSchema = true)
abstract class PrivateDatabase : RoomDatabase() {
    abstract fun dao(): PrivateDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context, PrivateDatabase::class.java, "private.db").build() }
}

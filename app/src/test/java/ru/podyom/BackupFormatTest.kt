package ru.podyom

import org.junit.Test
import org.junit.Assert.*
import ru.podyom.data.codec
import ru.podyom.ui.Backup

class BackupFormatTest {
    @Test fun readsVersionOneBackupWithoutGroupsOrNewSchedulingFields() {
        val backup = codec.decodeFromString<Backup>(
            """{"version":1,"alarms":[{"plan":{"id":"school","hour":6,"skipEpochDays":[20783]},"profile":{"title":"Школа","qr":"test-code"}}]}"""
        )
        assertEquals(1, backup.version)
        assertTrue(backup.groups.isEmpty())
        val alarm = backup.alarms.single()
        assertNull(alarm.plan.groupId)
        assertFalse(alarm.plan.independent)
        assertEquals(setOf(20783L), alarm.plan.skipEpochDays)
        assertEquals("test-code", alarm.profile.qr)
        assertTrue(backup.settings.deduplicate)
    }
}

package com.example.mob_dev_portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FilesystemQuarantineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun moveAside_renames_primary_and_sidecars_with_tag() {
        val dir = tempFolder.newFolder("databases")
        File(dir, "aura.db").writeText("main")
        File(dir, "aura.db-wal").writeText("wal")
        val quarantine = FilesystemQuarantine(clock = { 12345L })

        quarantine.moveAside(dir, tag = "legacy")

        assertFalse("primary must have been moved", File(dir, "aura.db").exists())
        assertTrue(File(dir, "aura.db.legacy-12345").exists())
        assertTrue(File(dir, "aura.db-wal.legacy-12345").exists())
    }

    @Test
    fun moveAside_does_nothing_when_primary_missing() {
        val dir = tempFolder.newFolder("databases")
        val quarantine = FilesystemQuarantine()
        quarantine.moveAside(dir, tag = "legacy")
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun moveAside_throws_when_rename_fails_rather_than_deleting() {
        val dir = tempFolder.newFolder("databases")
        val original = File(dir, "aura.db")
        original.writeText("precious user data")
        val quarantine = FilesystemQuarantine(renamer = { _, _ -> false })

        try {
            quarantine.moveAside(dir, tag = "legacy")
            fail("Expected IOException when rename fails")
        } catch (expected: IOException) {
            assertTrue("File must still exist after a failed quarantine", original.exists())
            assertEquals("precious user data", original.readText())
        }
    }
}

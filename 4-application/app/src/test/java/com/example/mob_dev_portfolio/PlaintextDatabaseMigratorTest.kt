package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.security.PlaintextDatabaseMigrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaintextDatabaseMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isLikelyPlaintextSqlite_returns_false_for_missing_file() {
        val missing = File(tempFolder.root, "does-not-exist.db")
        assertFalse(PlaintextDatabaseMigrator.isLikelyPlaintextSqlite(missing))
    }

    @Test
    fun isLikelyPlaintextSqlite_returns_false_for_random_bytes() {
        val file = tempFolder.newFile("random.db")
        file.writeBytes(ByteArray(64) { it.toByte() })
        assertFalse(PlaintextDatabaseMigrator.isLikelyPlaintextSqlite(file))
    }

    @Test
    fun isLikelyPlaintextSqlite_returns_true_for_valid_sqlite_magic_header() {
        val file = tempFolder.newFile("looks-real.db")
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val padding = ByteArray(1024) { 0 }
        file.writeBytes(header + padding)
        assertTrue(PlaintextDatabaseMigrator.isLikelyPlaintextSqlite(file))
    }

    @Test
    fun isLikelyPlaintextSqlite_returns_false_for_tiny_file() {
        val file = tempFolder.newFile("tiny.db")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(PlaintextDatabaseMigrator.isLikelyPlaintextSqlite(file))
    }
}

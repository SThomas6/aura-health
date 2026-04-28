package com.example.mob_dev_portfolio.data.security

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import kotlinx.coroutines.runBlocking
import java.io.File

object PlaintextDatabaseMigrator {

    private const val TAG = "PlaintextMigrator"

    fun isLikelyPlaintextSqlite(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read < 16) return false
                val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                header.contentEquals(expected)
            }
        }.getOrDefault(false)
    }

    fun readLegacyRows(file: File): List<SymptomLogEntity> {
        if (!isLikelyPlaintextSqlite(file)) return emptyList()
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                if (!tableExists(db, "symptom_logs")) return@use emptyList<SymptomLogEntity>()
                val rows = mutableListOf<SymptomLogEntity>()
                db.rawQuery(
                    "SELECT id, symptomName, description, startEpochMillis, endEpochMillis, severity, medication, contextTags, notes, createdAtEpochMillis FROM symptom_logs",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(
                            SymptomLogEntity(
                                id = cursor.getLong(0),
                                symptomName = cursor.getString(1) ?: "",
                                description = cursor.getString(2) ?: "",
                                startEpochMillis = cursor.getLong(3),
                                endEpochMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                                severity = cursor.getInt(5),
                                medication = cursor.getString(6) ?: "",
                                contextTags = cursor.getString(7) ?: "",
                                notes = cursor.getString(8) ?: "",
                                createdAtEpochMillis = cursor.getLong(9),
                            ),
                        )
                    }
                }
                rows
            }
        }.onFailure { Log.w(TAG, "Failed to read legacy plaintext DB", it) }
            .getOrDefault(emptyList())
    }

    private suspend fun importInto(dao: SymptomLogDao, rows: List<SymptomLogEntity>): Int {
        var imported = 0
        rows.forEach { row ->
            runCatching { dao.insert(row) }
                .onSuccess { imported += 1 }
                .onFailure { Log.w(TAG, "Failed to import legacy row id=${row.id}", it) }
        }
        return imported
    }

    fun importBlocking(dao: SymptomLogDao, rows: List<SymptomLogEntity>): Int =
        runBlocking { importInto(dao, rows) }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(name),
        ).use { it.moveToFirst() }
    }
}

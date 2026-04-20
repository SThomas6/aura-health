package com.example.mob_dev_portfolio.data.report

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted metadata for a single generated health-report PDF.
 *
 * The compressed PDF itself lives on disk under
 * `cacheDir/reports/<compressedFileName>`. This table is the authoritative
 * index of every report the user has generated — the history screen
 * observes it via Flow so new rows appear instantly.
 *
 * Deletion is two-step (see [ReportArchiveRepository.delete]): the file
 * is removed from disk first, and only then is this row deleted, so we
 * never orphan a metadata entry pointing at a file we couldn't delete.
 *
 * The file name is stored as a *name*, not an absolute path, so that a
 * reinstall / migration doesn't break rows pointing at an old cache
 * directory — callers resolve it against the current `reports/` dir.
 */
@Entity(
    tableName = "report_archives",
    indices = [
        // Unique index on the file name so the "register on successful
        // generate" call can use ABORT and know a collision means
        // something weird happened (the generator timestamps filenames
        // to the millisecond so collisions should never occur in
        // practice).
        Index(value = ["compressedFileName"], unique = true),
    ],
)
data class ReportArchiveEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Filename of the GZIP-compressed PDF under `cacheDir/reports/`. */
    val compressedFileName: String,
    /** Wall-clock time the report was generated. Used for sort + label. */
    val generatedAtEpochMillis: Long,
    /** Size of the raw PDF before compression — shown as context. */
    val uncompressedBytes: Long,
    /** Actual bytes on disk. */
    val compressedBytes: Long,
    /**
     * Aggregate from the snapshot used to render the PDF. Cached here
     * so the history list can show "12 entries" without re-running the
     * database aggregation.
     */
    val totalLogCount: Int,
    /**
     * `AVG(severity)` at generation time. Null when the database had
     * zero symptom logs — SQLite returns NULL from AVG of zero rows.
     */
    val averageSeverity: Double?,
)

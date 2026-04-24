package com.example.mob_dev_portfolio.data.photo

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.mob_dev_portfolio.data.SymptomLogEntity

/**
 * One row per photo attached to a symptom log (FR-PA-01, up to 3 per log —
 * cap enforced in the repository rather than the schema because 3 is a UX
 * decision that may move; the DB doesn't care).
 *
 * Storage layout:
 *
 *  - `storageFileName` is the opaque `<uuid>.enc` basename under
 *    `filesDir/symptom_photos/`. We keep only the basename (not the
 *    absolute path) so backups, reinstalls, and moves of the app's
 *    internal directory don't leave stale absolute paths behind. The
 *    repository resolves the real file by prefixing with the current
 *    `filesDir`.
 *
 *  - `symptomLogId` has a cascade-delete FK. This is the spine of
 *    NFR-PA-05: deleting a log in Room drops the photo rows atomically.
 *    The *file-system* cleanup piggybacks on the repository's
 *    `deleteForLog(logId)` path (called before the row delete so the
 *    file list is still discoverable) — the DB cascade is the
 *    safety-net that catches any row Room deletes on behalf of the FK
 *    rule without going through the repository (e.g. a test that
 *    deletes the parent directly).
 *
 *  - `createdAtEpochMillis` is set at capture time so the gallery
 *    thumbnail strip can show photos in the order the user attached
 *    them (stable across edits even when IDs are re-allocated).
 */
@Entity(
    tableName = "symptom_photos",
    foreignKeys = [
        ForeignKey(
            entity = SymptomLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["symptomLogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["symptomLogId"])],
)
data class SymptomPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symptomLogId: Long,
    val storageFileName: String,
    val createdAtEpochMillis: Long,
)

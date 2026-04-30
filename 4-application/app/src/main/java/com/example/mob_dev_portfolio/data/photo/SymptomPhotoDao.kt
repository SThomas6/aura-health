package com.example.mob_dev_portfolio.data.photo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO over the photo-attachment table.
 *
 * Insert uses [OnConflictStrategy.ABORT] (rather than REPLACE) because
 * a primary-key collision would indicate a programming error — every
 * insert here originates from a fresh `autoGenerate` id. We'd rather
 * surface that bug as a SQLiteConstraintException than silently
 * overwrite an existing row and orphan its on-disk encrypted file.
 */
@Dao
interface SymptomPhotoDao {

    /** Insert a freshly-captured photo row. Aborts on PK collision (see DAO KDoc). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SymptomPhotoEntity): Long

    /**
     * Observe the photo attachments for a given log, ordered by
     * capture time ascending (oldest first). Drives both the
     * in-editor thumbnail strip and the detail-screen gallery.
     */
    @Query(
        "SELECT * FROM symptom_photos WHERE symptomLogId = :logId ORDER BY createdAtEpochMillis ASC",
    )
    fun observeForLog(logId: Long): Flow<List<SymptomPhotoEntity>>

    /**
     * One-shot list for a log — used by the PDF generator, which
     * takes a single consistent snapshot rather than subscribing to
     * a long-lived Flow.
     */
    @Query(
        "SELECT * FROM symptom_photos WHERE symptomLogId = :logId ORDER BY createdAtEpochMillis ASC",
    )
    suspend fun listForLog(logId: Long): List<SymptomPhotoEntity>

    /**
     * Count helper so the repository can enforce the FR-PA-01 cap
     * (≤ 3 photos per log) atomically at insert time.
     */
    @Query("SELECT COUNT(*) FROM symptom_photos WHERE symptomLogId = :logId")
    suspend fun countForLog(logId: Long): Int

    /** Lookup-by-id used before an unlink so the repository can resolve the file path. */
    @Query("SELECT * FROM symptom_photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SymptomPhotoEntity?

    /** Delete a single photo row. The repository deletes the on-disk blob first. */
    @Query("DELETE FROM symptom_photos WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Delete every photo row for a log. Called by the repository in
     * tandem with file-system unlinks; the FK cascade on the parent
     * is the safety-net for callers that delete logs directly.
     */
    @Query("DELETE FROM symptom_photos WHERE symptomLogId = :logId")
    suspend fun deleteForLog(logId: Long)
}

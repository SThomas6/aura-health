package com.example.mob_dev_portfolio.data.photo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomPhotoDao {

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

    @Query("SELECT * FROM symptom_photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SymptomPhotoEntity?

    @Query("DELETE FROM symptom_photos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM symptom_photos WHERE symptomLogId = :logId")
    suspend fun deleteForLog(logId: Long)
}

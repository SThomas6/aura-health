package com.example.mob_dev_portfolio.data.medication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO over the dose-event log (one row per fired reminder + the
 * user's eventual response).
 *
 * The two `observeSince` flows are deliberately partial — most history
 * UI only ever wants the last 30 days (FR-MR-06), and pulling everything
 * just to filter client-side would scale poorly as the table grows. The
 * `sinceEpochMillis` cutoff matches the retention floor enforced by
 * [deleteOlderThan].
 */
@Dao
interface DoseEventDao {

    /** Insert a freshly-fired reminder row (status = `PENDING`); returns the new row id. */
    @Insert
    suspend fun insert(event: DoseEventEntity): Long

    @Query(
        """
        UPDATE medication_dose_events
        SET status = :status, actedAtEpochMillis = :actedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: Long, status: String, actedAt: Long)

    @Query(
        """
        SELECT * FROM medication_dose_events
        WHERE actedAtEpochMillis >= :sinceEpochMillis
        ORDER BY scheduledAtEpochMillis DESC
        """,
    )
    fun observeSince(sinceEpochMillis: Long): Flow<List<DoseEventEntity>>

    @Query(
        """
        SELECT * FROM medication_dose_events
        WHERE medicationId = :medicationId
          AND actedAtEpochMillis >= :sinceEpochMillis
        ORDER BY scheduledAtEpochMillis DESC
        """,
    )
    fun observeForReminderSince(medicationId: Long, sinceEpochMillis: Long): Flow<List<DoseEventEntity>>

    /**
     * Garbage-collection hook called from the list/history screens on
     * view entry. 30 days is the retention floor promised in FR-MR-06;
     * anything older is no longer displayable so keeping it around
     * just grows the encrypted database file.
     */
    @Query("DELETE FROM medication_dose_events WHERE actedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}

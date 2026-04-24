package com.example.mob_dev_portfolio.data.medication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseEventDao {

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

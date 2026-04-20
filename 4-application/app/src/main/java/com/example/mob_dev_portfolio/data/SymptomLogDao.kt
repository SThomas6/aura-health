package com.example.mob_dev_portfolio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SymptomLogEntity): Long

    @Update
    suspend fun update(entity: SymptomLogEntity): Int

    @Query("SELECT * FROM symptom_logs ORDER BY startEpochMillis DESC")
    fun observeAll(): Flow<List<SymptomLogEntity>>

    @Query(
        """
        SELECT * FROM symptom_logs
        WHERE (:query IS NULL
                OR symptomName LIKE '%' || :query || '%'
                OR description LIKE '%' || :query || '%'
                OR notes LIKE '%' || :query || '%'
                OR medication LIKE '%' || :query || '%')
          AND severity >= :minSeverity
          AND severity <= :maxSeverity
          AND (:startAfter IS NULL OR startEpochMillis >= :startAfter)
          AND (:startBefore IS NULL OR startEpochMillis <= :startBefore)
        ORDER BY
            CASE WHEN :sortKey = 'DATE_ASC' THEN startEpochMillis END ASC,
            CASE WHEN :sortKey = 'SEVERITY_DESC' THEN severity END DESC,
            CASE WHEN :sortKey = 'SEVERITY_ASC' THEN severity END ASC,
            CASE WHEN :sortKey = 'NAME_ASC' THEN LOWER(symptomName) END ASC,
            CASE WHEN :sortKey = 'DATE_DESC' THEN startEpochMillis END DESC,
            startEpochMillis DESC
        """,
    )
    fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ): Flow<List<SymptomLogEntity>>

    @Query("SELECT * FROM symptom_logs WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<SymptomLogEntity?>

    @Query("SELECT COUNT(*) FROM symptom_logs")
    fun observeCount(): Flow<Int>

    /**
     * One-shot chronological fetch — **oldest first** — used by the PDF
     * health report. Kept as a suspending non-Flow so the report builder
     * can pull a single consistent snapshot of the table without
     * subscribing to a long-lived Flow.
     */
    @Query("SELECT * FROM symptom_logs ORDER BY startEpochMillis ASC")
    suspend fun listChronologicalAsc(): List<SymptomLogEntity>

    /**
     * Aggregate helpers pushed down to SQL so the database handles the
     * `COUNT` / `AVG` without the JVM materialising the whole table.
     * [averageSeverity] returns `null` when the table is empty (SQLite's
     * `AVG` on zero rows) — callers must special-case that for display.
     */
    @Query("SELECT COUNT(*) FROM symptom_logs")
    suspend fun totalCount(): Int

    @Query("SELECT AVG(severity) FROM symptom_logs")
    suspend fun averageSeverity(): Double?

    @Query("DELETE FROM symptom_logs WHERE id = :id")
    suspend fun delete(id: Long)
}

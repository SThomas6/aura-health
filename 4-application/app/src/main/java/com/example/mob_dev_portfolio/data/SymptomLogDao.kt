package com.example.mob_dev_portfolio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `symptom_logs` table — the heart of the app's local
 * storage. Mixes [Flow]-returning observers (for reactive UI binding via
 * `collectAsStateWithLifecycle`) with `suspend` one-shot reads (for the
 * report builder and aggregate queries).
 *
 * The filtering query is intentionally pushed all the way down to SQL
 * rather than re-implemented in Kotlin: it ensures the History screen
 * remains responsive once the table holds thousands of rows, and it
 * means the partial-text match runs in SQLite's optimised LIKE rather
 * than a JVM regex.
 */
@Dao
interface SymptomLogDao {

    /**
     * REPLACE conflict strategy: the editor reuses the row's id on
     * edit, so an `insert` of an existing id atomically overwrites.
     * Returns the new (or replaced) row id so callers can navigate to
     * it without a follow-up select.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SymptomLogEntity): Long

    @Update
    suspend fun update(entity: SymptomLogEntity): Int

    /**
     * Reactive descending-by-start-time stream. Drives any screen that
     * doesn't need filtering — primarily the Home dashboard preview.
     */
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
            -- Date sorts use the *end* time so "newest" means
            -- "most recently ended", and ongoing logs (NULL endEpochMillis)
            -- fall back to startEpochMillis. The History screen partitions
            -- the result into Ongoing / Ended sections, so within-section
            -- ordering is what this clause controls.
            CASE WHEN :sortKey = 'DATE_ASC'
                THEN COALESCE(endEpochMillis, startEpochMillis) END ASC,
            CASE WHEN :sortKey = 'SEVERITY_DESC' THEN severity END DESC,
            CASE WHEN :sortKey = 'SEVERITY_ASC' THEN severity END ASC,
            CASE WHEN :sortKey = 'NAME_ASC' THEN LOWER(symptomName) END ASC,
            CASE WHEN :sortKey = 'DATE_DESC'
                THEN COALESCE(endEpochMillis, startEpochMillis) END DESC,
            COALESCE(endEpochMillis, startEpochMillis) DESC
        """,
    )
    /**
     * Drives the filtered History screen. Each parameter is nullable
     * (or has a sentinel) to support "filter unset" without a separate
     * query per axis. The `:sortKey` string maps onto a CASE/WHEN
     * cascade above so a single prepared statement can serve every
     * sort the UI exposes — adding a new sort means adding a new
     * CASE arm here, not a new method.
     */
    fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ): Flow<List<SymptomLogEntity>>

    /** Single-row reactive read. Emits `null` when the row is deleted. */
    @Query("SELECT * FROM symptom_logs WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<SymptomLogEntity?>

    /** Drives the home dashboard's "logs to date" stat. */
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

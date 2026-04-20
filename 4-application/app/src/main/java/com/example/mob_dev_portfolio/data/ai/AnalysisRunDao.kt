package com.example.mob_dev_portfolio.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room access for AI analysis history.
 *
 * The reads are all Flow-returning so the Compose layer binds reactively
 * — the moment the worker commits a new row the history list recomposes.
 * Writes are suspending because insertion happens off the main thread
 * from [com.example.mob_dev_portfolio.work.AnalysisWorker].
 */
@Dao
interface AnalysisRunDao {

    /**
     * Insert a new run and return the generated rowId so the caller can
     * immediately link the associated symptom logs via
     * [insertCrossRefs]. Conflict strategy is REPLACE for parity with the
     * symptom-log DAO — a pre-assigned id would overwrite an existing row
     * rather than throw, which is the expected behaviour for retries.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: AnalysisRunEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(refs: List<AnalysisRunLogCrossRef>)

    /**
     * Atomic "write a run plus its logs" helper. Avoiding the two-step
     * call at the repository layer keeps the writes inside a single
     * transaction — if the cross-ref insert throws the run row is rolled
     * back and we don't orphan a history entry with no logs attached.
     */
    @Transaction
    suspend fun insertRunWithLogs(run: AnalysisRunEntity, logIds: List<Long>): Long {
        val runId = insertRun(run)
        if (logIds.isNotEmpty()) {
            insertCrossRefs(logIds.map { AnalysisRunLogCrossRef(runId = runId, logId = it) })
        }
        return runId
    }

    /**
     * History list query — newest first. Acceptance criterion says
     * "sorted from newest to oldest"; `completedAtEpochMillis` is the
     * moment the worker finished so it's the most user-meaningful sort
     * key (using the auto-id would be correct-enough but less readable
     * in a debugger).
     */
    @Query("SELECT * FROM analysis_runs ORDER BY completedAtEpochMillis DESC")
    fun observeAll(): Flow<List<AnalysisRunEntity>>

    /**
     * Single-run fetch for the detail screen. Returns null if the row
     * has been deleted behind the UI's back — the screen renders a
     * "not available" state rather than crashing.
     */
    @Query("SELECT * FROM analysis_runs WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<AnalysisRunEntity?>

    /** Non-flow read used by the worker to compute whether this is the first run. */
    @Query("SELECT * FROM analysis_runs ORDER BY completedAtEpochMillis DESC LIMIT 1")
    suspend fun latest(): AnalysisRunEntity?

    /**
     * One-shot oldest-first dump used by the PDF report builder so the
     * generated document reads as a chronological journal (symptom logs
     * + AI insights) rather than in "most recent first" UI order.
     */
    @Query("SELECT * FROM analysis_runs ORDER BY completedAtEpochMillis ASC")
    suspend fun listChronologicalAsc(): List<AnalysisRunEntity>

    /** Cross-refs for a given run, used by the detail screen for the "N logs analysed" line. */
    @Query("SELECT logId FROM analysis_run_logs WHERE runId = :runId")
    fun observeLogIdsFor(runId: Long): Flow<List<Long>>

    @Query("DELETE FROM analysis_runs WHERE id = :id")
    suspend fun delete(id: Long)
}

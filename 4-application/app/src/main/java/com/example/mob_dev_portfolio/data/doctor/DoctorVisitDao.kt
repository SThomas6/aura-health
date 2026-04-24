package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DoctorVisitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(entity: DoctorVisitEntity): Long

    @Update
    suspend fun updateVisit(entity: DoctorVisitEntity): Int

    @Query("DELETE FROM doctor_visits WHERE id = :id")
    suspend fun deleteVisit(id: Long)

    /** Newest visit first — matches the list-screen sort. */
    @Query("SELECT * FROM doctor_visits ORDER BY visitDateEpochMillis DESC, id DESC")
    fun observeVisits(): Flow<List<DoctorVisitEntity>>

    @Query("SELECT * FROM doctor_visits WHERE id = :id LIMIT 1")
    fun observeVisit(id: Long): Flow<DoctorVisitEntity?>

    @Query("SELECT * FROM doctor_visits WHERE id = :id LIMIT 1")
    suspend fun getVisit(id: Long): DoctorVisitEntity?

    // ── covered-logs join table ─────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCoveredLog(link: DoctorVisitCoveredLog)

    @Query("DELETE FROM doctor_visit_covered_logs WHERE visitId = :visitId")
    suspend fun clearCoveredLogsForVisit(visitId: Long)

    @Query("SELECT logId FROM doctor_visit_covered_logs WHERE visitId = :visitId")
    fun observeCoveredLogIdsForVisit(visitId: Long): Flow<List<Long>>

    /**
     * All log ids the user has ever cleared, across every visit. This is
     * the single source the AI sanitiser reads when deciding which logs
     * to drop before building the Gemini prompt.
     */
    @Query("SELECT DISTINCT logId FROM doctor_visit_covered_logs")
    fun observeAllClearedLogIds(): Flow<List<Long>>

    /**
     * Snapshot variant of [observeAllClearedLogIds] for the AI pipeline,
     * which runs inside a single worker coroutine rather than a flow
     * collector — the pipeline needs a here-and-now set, not a live
     * subscription.
     */
    @Query("SELECT DISTINCT logId FROM doctor_visit_covered_logs")
    suspend fun listAllClearedLogIds(): List<Long>

    /**
     * The visit that cleared a given log (if any). The log-detail badge
     * reads this so the user sees "Reviewed 14 Apr by Dr. Smith — no
     * concern" with a tap-through.
     */
    @Query(
        """
        SELECT v.* FROM doctor_visits v
        INNER JOIN doctor_visit_covered_logs l ON l.visitId = v.id
        WHERE l.logId = :logId
        ORDER BY v.visitDateEpochMillis DESC
        LIMIT 1
        """,
    )
    fun observeClearingVisitForLog(logId: Long): Flow<DoctorVisitEntity?>
}

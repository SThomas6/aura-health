package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the doctor-visit half of the Doctor Visits feature.
 * Owns the `doctor_visits` parent table and the `doctor_visit_covered_logs`
 * join — the latter is the source of truth for which symptom logs the
 * AI sanitiser must drop ("the doctor cleared this, leave it alone").
 *
 * Diagnoses live in the sibling [DoctorDiagnosisDao] but cascade off
 * the visit row via foreign key, so deleting a visit also wipes its
 * diagnoses and the cleared-log links — the only deletion surface a
 * caller needs.
 */
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

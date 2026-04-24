package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DoctorDiagnosisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiagnosis(entity: DoctorDiagnosisEntity): Long

    @Update
    suspend fun updateDiagnosis(entity: DoctorDiagnosisEntity): Int

    @Query("DELETE FROM doctor_diagnoses WHERE id = :id")
    suspend fun deleteDiagnosis(id: Long)

    @Query("DELETE FROM doctor_diagnoses WHERE visitId = :visitId")
    suspend fun deleteDiagnosesForVisit(visitId: Long)

    @Query("SELECT * FROM doctor_diagnoses WHERE visitId = :visitId ORDER BY createdAtEpochMillis ASC, id ASC")
    fun observeDiagnosesForVisit(visitId: Long): Flow<List<DoctorDiagnosisEntity>>

    @Query("SELECT * FROM doctor_diagnoses WHERE visitId = :visitId ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun listDiagnosesForVisit(visitId: Long): List<DoctorDiagnosisEntity>

    /** Every diagnosis in the system. Powers the "link new log to a diagnosis" picker. */
    @Query("SELECT * FROM doctor_diagnoses ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observeAllDiagnoses(): Flow<List<DoctorDiagnosisEntity>>

    @Query("SELECT * FROM doctor_diagnoses ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun listAllDiagnoses(): List<DoctorDiagnosisEntity>

    @Query("SELECT * FROM doctor_diagnoses WHERE id = :id LIMIT 1")
    suspend fun getDiagnosis(id: Long): DoctorDiagnosisEntity?

    // ── diagnosis-log join table ────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiagnosisLog(link: DoctorDiagnosisLog)

    @Query("DELETE FROM doctor_diagnosis_logs WHERE diagnosisId = :diagnosisId")
    suspend fun clearLogsForDiagnosis(diagnosisId: Long)

    @Query("DELETE FROM doctor_diagnosis_logs WHERE logId = :logId")
    suspend fun clearDiagnosisLinksForLog(logId: Long)

    @Query("SELECT logId FROM doctor_diagnosis_logs WHERE diagnosisId = :diagnosisId")
    fun observeLogIdsForDiagnosis(diagnosisId: Long): Flow<List<Long>>

    @Query("SELECT logId FROM doctor_diagnosis_logs WHERE diagnosisId = :diagnosisId")
    suspend fun listLogIdsForDiagnosis(diagnosisId: Long): List<Long>

    @Query("SELECT * FROM doctor_diagnosis_logs")
    suspend fun listAllDiagnosisLinks(): List<DoctorDiagnosisLog>

    /**
     * The diagnosis a given log is linked to (if any). Feeds the
     * log-detail badge and the AI-prompt annotation.
     */
    @Query(
        """
        SELECT d.* FROM doctor_diagnoses d
        INNER JOIN doctor_diagnosis_logs l ON l.diagnosisId = d.id
        WHERE l.logId = :logId
        ORDER BY d.createdAtEpochMillis DESC
        LIMIT 1
        """,
    )
    fun observeDiagnosisForLog(logId: Long): Flow<DoctorDiagnosisEntity?>
}

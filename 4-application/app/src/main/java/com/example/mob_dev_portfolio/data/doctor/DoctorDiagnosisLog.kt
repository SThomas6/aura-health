package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.example.mob_dev_portfolio.data.SymptomLogEntity

/**
 * Join table: a symptom log the user has tagged as "part of this
 * diagnosis." Unlike [DoctorVisitCoveredLog], these logs are NOT
 * excluded from AI analysis — they're annotated with the diagnosis
 * label so the model treats them as known-context rather than a fresh
 * signal.
 *
 * A single log can be pinned to a diagnosis OR marked as cleared, but
 * the UI doesn't enforce mutual exclusion — if the user somehow lands
 * in both states the AI pipeline resolves it by preferring the cleared
 * side (clearest user intent: "ignore this").
 */
@Entity(
    tableName = "doctor_diagnosis_logs",
    primaryKeys = ["diagnosisId", "logId"],
    foreignKeys = [
        ForeignKey(
            entity = DoctorDiagnosisEntity::class,
            parentColumns = ["id"],
            childColumns = ["diagnosisId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SymptomLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("logId")],
)
data class DoctorDiagnosisLog(
    val diagnosisId: Long,
    val logId: Long,
)

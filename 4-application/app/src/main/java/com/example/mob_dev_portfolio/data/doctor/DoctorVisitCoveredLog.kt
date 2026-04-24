package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.example.mob_dev_portfolio.data.SymptomLogEntity

/**
 * Join table: a symptom log the doctor explicitly reviewed and cleared
 * during [DoctorVisitEntity]. Rows here tell the AI pipeline "do not
 * analyse this log — the user has already been told it's nothing to
 * worry about."
 *
 * Cascades on both sides:
 *   - Deleting the visit drops the join row (so the log rejoins analysis).
 *   - Deleting the underlying symptom log drops the join row too.
 */
@Entity(
    tableName = "doctor_visit_covered_logs",
    primaryKeys = ["visitId", "logId"],
    foreignKeys = [
        ForeignKey(
            entity = DoctorVisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visitId"],
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
data class DoctorVisitCoveredLog(
    val visitId: Long,
    val logId: Long,
)

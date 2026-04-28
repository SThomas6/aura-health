package com.example.mob_dev_portfolio.data.condition

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.mob_dev_portfolio.data.SymptomLogEntity

/**
 * User-declared chronic / pre-existing health condition.
 *
 * Distinct from [com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosisEntity]:
 *   - A `DoctorDiagnosis` is something the user logs as having been
 *     **diagnosed at a specific doctor visit**. It always has a parent
 *     `DoctorVisit`, with the doctor's name + notes from that visit.
 *   - A `HealthCondition` is a **standalone fact about the user**:
 *     "I have Type 2 diabetes." It has no visit attached, may pre-date
 *     using the app, and is captured during onboarding or via the
 *     dedicated Conditions settings screen.
 *
 * The two are surfaced separately in the UI but both flow into the AI's
 * doctor-context bundle so the model can reason about already-explained
 * symptoms regardless of whether the user has a doctor visit on file.
 */
@Entity(tableName = "health_conditions")
data class HealthConditionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /**
     * Human-readable condition name (e.g. "Type 2 Diabetes",
     * "Hypothyroidism"). Free-text — we don't constrain to a controlled
     * vocabulary because the user isn't a clinician and rough names
     * still carry useful context for the AI.
     */
    val name: String,
    /** Optional extra detail the user wants associated with the condition. */
    val notes: String,
    val createdAtEpochMillis: Long,
)

/**
 * Join table linking symptom logs to user-declared conditions. Many-to-
 * many in principle but in the UI a log is linked to at most one
 * condition; the schema permits multi-link so a future "this could be
 * either of two conditions" affordance doesn't need a migration.
 */
@Entity(
    tableName = "health_condition_logs",
    primaryKeys = ["conditionId", "logId"],
    foreignKeys = [
        ForeignKey(
            entity = HealthConditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["conditionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SymptomLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["logId"])],
)
data class HealthConditionLog(
    val conditionId: Long,
    val logId: Long,
)

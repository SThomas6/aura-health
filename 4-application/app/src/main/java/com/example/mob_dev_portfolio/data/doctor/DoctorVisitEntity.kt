package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per GP / consultant visit the user has logged.
 *
 * The visit is the anchor point for two downstream concepts:
 *   - A set of symptom logs the doctor *reviewed and cleared* — tracked
 *     via [DoctorVisitCoveredLog]. Those logs drop out of AI analysis
 *     entirely (the user's explicit instruction: "if the doctor checked
 *     them, ignore them").
 *   - A list of *identified diagnoses* — tracked via [DoctorDiagnosisEntity].
 *     Each diagnosis can be tied to specific symptom logs that are part
 *     of that condition (via [DoctorDiagnosisLog]).
 *
 * [doctorName] is captured but is treated as PII: the AI pipeline never
 * serializes it. Only diagnosis labels + brief symptom history (name + date)
 * reach the prompt.
 */
@Entity(tableName = "doctor_visits")
data class DoctorVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val doctorName: String,
    val visitDateEpochMillis: Long,
    val summary: String,
    val createdAtEpochMillis: Long,
)

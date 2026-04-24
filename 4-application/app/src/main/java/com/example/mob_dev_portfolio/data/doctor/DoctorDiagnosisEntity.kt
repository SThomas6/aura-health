package com.example.mob_dev_portfolio.data.doctor

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A distinct issue / diagnosis the doctor flagged during a visit. One
 * visit can produce many diagnoses ("persistent migraine" *and* "tension
 * neck" from the same consultation).
 *
 * Diagnoses drive the "Known diagnoses" section of the AI prompt: the
 * label is surfaced verbatim so the model knows the user has already
 * been assessed for that condition. The free-text [notes] field stays
 * on-device and is never sent to the model (it commonly contains the
 * doctor's prescription, surgery name, follow-up dates — out-of-scope
 * PII that isn't needed for analysis).
 *
 * Symptom logs can be tied to a diagnosis via [DoctorDiagnosisLog]. The
 * AI pipeline reads those links to annotate each log with its diagnosis
 * label — so the model sees "Headache, severity 7 (linked to
 * diagnosis: Migraine)" rather than treating it as a fresh signal.
 */
@Entity(
    tableName = "doctor_diagnoses",
    foreignKeys = [
        ForeignKey(
            entity = DoctorVisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("visitId")],
)
data class DoctorDiagnosisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val visitId: Long,
    val label: String,
    val notes: String,
    val createdAtEpochMillis: Long,
)

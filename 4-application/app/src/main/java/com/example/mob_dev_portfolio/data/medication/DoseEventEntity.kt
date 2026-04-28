package com.example.mob_dev_portfolio.data.medication

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only history of every reminder fire + subsequent user action.
 *
 * Lifecycle of a row:
 *   - Inserted as `PENDING` the moment the notification is posted.
 *   - Updated to `TAKEN` when the user taps the notification's Taken
 *     action.
 *   - Updated to `SNOOZED` when the user taps Snooze (and a fresh
 *     alarm is scheduled 15 minutes later, which itself produces a
 *     new `PENDING` row on the re-fire).
 *   - Stays `PENDING` if the user dismisses the notification without
 *     acting; the history view surfaces any `PENDING` older than
 *     [MISSED_GRACE_MILLIS] as "Missed" without needing a background
 *     sweeper to rewrite the row.
 *
 * Foreign key cascades on delete so removing a reminder purges its
 * history too — matches the acceptance-criterion "deleting a reminder"
 * expectation and avoids orphaned rows.
 */
@Entity(
    tableName = "medication_dose_events",
    foreignKeys = [
        ForeignKey(
            entity = MedicationReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId"), Index("scheduledAtEpochMillis")],
)
data class DoseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val medicationId: Long,
    /** Clock time the reminder was *meant* to fire. */
    val scheduledAtEpochMillis: Long,
    /** `PENDING`, `TAKEN`, or `SNOOZED` — see [DoseStatus]. */
    val status: String,
    /** Last time the user acted on this row (or the fire time for PENDING). */
    val actedAtEpochMillis: Long,
)

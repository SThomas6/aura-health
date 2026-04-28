package com.example.mob_dev_portfolio.data.medication

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per medication-reminder configuration. Independent of any
 * actual dose event: an entry here is "a schedule"; every time the
 * schedule fires a row is written to [DoseEventEntity] so history
 * queries can hit a single flat table.
 *
 * Frequency is modelled as a discriminated trio rather than a single
 * rrule string:
 *
 *  - `frequencyKind = "DAILY"` → fires every day at [timeOfDayMinutes]
 *  - `frequencyKind = "WEEKLY"` → fires on the days encoded in
 *    [daysOfWeekMask] (bit 0 = Monday … bit 6 = Sunday, ISO week) at
 *    [timeOfDayMinutes]
 *  - `frequencyKind = "ONE_OFF"` → a single alarm at the absolute
 *    [oneOffAtEpochMillis]; [timeOfDayMinutes] and [daysOfWeekMask]
 *    are 0 and ignored.
 *
 * Schema is flat-string-discriminated (not a polymorphic @Entity
 * hierarchy) because the three variants share almost every field and
 * Room's migration story for polymorphic entities is a minefield. The
 * app-layer domain model in [MedicationRepository] restores a proper
 * sealed class.
 */
@Entity(tableName = "medication_reminders")
data class MedicationReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val dosage: String,
    val frequencyKind: String,
    val timeOfDayMinutes: Int,
    val daysOfWeekMask: Int,
    val oneOffAtEpochMillis: Long?,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
)

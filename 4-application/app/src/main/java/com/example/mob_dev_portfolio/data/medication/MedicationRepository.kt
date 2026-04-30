package com.example.mob_dev_portfolio.data.medication

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Status of a single dose event.
 *
 * `PENDING` rows that age past [MISSED_GRACE_MILLIS] are surfaced in
 * the UI as "Missed" — we don't rewrite the row because the receiver
 * may run *after* the user has opened the history screen and a status
 * change of a visible row would produce a distracting flicker. The
 * query-side classification keeps the database source-of-truth simple.
 */
enum class DoseStatus { Pending, Taken, Snoozed, Missed }

const val MISSED_GRACE_MILLIS: Long = 2 * 60 * 60 * 1000L

/**
 * 30-day dose-history retention window (FR-MR-06). Events older than
 * this get pruned on cold start so the history list can't grow
 * unbounded on a long-lived install.
 */
const val HISTORY_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

/**
 * Grace window within which a fired notification can still be acted
 * on before history marks it as "Missed". Two hours is long enough to
 * catch most real-world "I'll get to it after this meeting" delays
 * without making the history view misleading.
 */

/** Sealed domain model for the three frequency variants. */
sealed class ReminderFrequency {
    data object Daily : ReminderFrequency()

    /**
     * [daysMask] — bit 0 = Monday, bit 6 = Sunday (ISO week). A zero
     * mask is accepted but produces no alarms; the editor UI refuses
     * to save in that state so it's a theoretical-only case here.
     */
    data class WeeklyDays(val daysMask: Int) : ReminderFrequency()

    /** [atEpochMillis] is the absolute instant to fire once. */
    data class OneOff(val atEpochMillis: Long) : ReminderFrequency()

    companion object {
        const val MASK_MON = 1 shl 0
        const val MASK_TUE = 1 shl 1
        const val MASK_WED = 1 shl 2
        const val MASK_THU = 1 shl 3
        const val MASK_FRI = 1 shl 4
        const val MASK_SAT = 1 shl 5
        const val MASK_SUN = 1 shl 6
        const val MASK_ALL_DAYS = 0x7F
    }
}

data class MedicationReminder(
    val id: Long,
    val name: String,
    val dosage: String,
    val frequency: ReminderFrequency,
    /** Minutes from midnight. Meaningless for [ReminderFrequency.OneOff]. */
    val timeOfDayMinutes: Int,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
)

data class DoseEvent(
    val id: Long,
    val medicationId: Long,
    val scheduledAtEpochMillis: Long,
    val status: DoseStatus,
    val actedAtEpochMillis: Long,
)

/**
 * Thin wrapper over the two DAOs that also handles the
 * entity ↔ domain-model hop so nothing downstream has to touch the
 * string discriminator or the bitmask directly.
 */
open class MedicationRepository(
    private val reminderDao: MedicationReminderDao,
    private val doseEventDao: DoseEventDao,
) {

    /** Hot stream of every saved reminder. The list rerenders on insert/update/delete. */
    open fun observeAll(): Flow<List<MedicationReminder>> =
        reminderDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** One-shot list of reminders — used by the scheduler when re-arming on boot. */
    open suspend fun listAll(): List<MedicationReminder> =
        reminderDao.listAll().map { it.toDomain() }

    /** Lookup by primary key, returning null if the row was deleted in flight. */
    open suspend fun getById(id: Long): MedicationReminder? =
        reminderDao.getById(id)?.toDomain()

    /**
     * Insert (id == 0) or update (id != 0) a reminder. Returns the
     * resulting row id so the editor can navigate back with a stable
     * handle. Note that REPLACE on the underlying DAO would also work
     * for updates but we go through `update` to preserve the existing
     * id rather than re-insert with a fresh one.
     */
    open suspend fun upsert(reminder: MedicationReminder): Long {
        val entity = reminder.toEntity()
        return if (reminder.id == 0L) reminderDao.insert(entity)
        else {
            reminderDao.update(entity)
            reminder.id
        }
    }

    /** Hard-delete a reminder. The FK cascade purges its dose history simultaneously. */
    open suspend fun delete(id: Long) = reminderDao.delete(id)

    /** Toggle enabled state without rewriting any other field. No-op if id missing. */
    open suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = reminderDao.getById(id) ?: return
        reminderDao.update(current.copy(enabled = enabled))
    }

    /**
     * Appends a new `PENDING` row for the moment a reminder fires. The
     * returned row id is embedded in the notification's action intents so
     * a subsequent Taken / Snooze tap can update the exact event without
     * re-querying for "the latest PENDING for this medication".
     */
    open suspend fun recordFired(medicationId: Long, scheduledAt: Long, firedAt: Long): Long {
        return doseEventDao.insert(
            DoseEventEntity(
                medicationId = medicationId,
                scheduledAtEpochMillis = scheduledAt,
                status = DoseStatus.Pending.storageKey,
                actedAtEpochMillis = firedAt,
            ),
        )
    }

    /** Flip a PENDING dose row to TAKEN. Called from the notification action receiver. */
    open suspend fun markTaken(eventId: Long, actedAt: Long) {
        doseEventDao.updateStatus(eventId, DoseStatus.Taken.storageKey, actedAt)
    }

    /** Flip a PENDING dose row to SNOOZED. The scheduler arms a fresh alarm separately. */
    open suspend fun markSnoozed(eventId: Long, actedAt: Long) {
        doseEventDao.updateStatus(eventId, DoseStatus.Snoozed.storageKey, actedAt)
    }

    /**
     * Returns every dose event from the last [windowMillis] ms. Rows
     * are already sorted newest-first by the DAO; the UI layer joins
     * them to the reminder names in-memory.
     */
    open fun observeEventsSince(windowMillis: Long, now: Long): Flow<List<DoseEvent>> =
        doseEventDao.observeSince(now - windowMillis).map { list -> list.map { it.toDomain(now) } }

    /**
     * Prune dose events older than [cutoffEpochMillis] (ms since epoch).
     * Returns the number of rows deleted so the caller can log a metric.
     * Wired into the cold-start retention sweep — see [HISTORY_RETENTION_MILLIS].
     */
    open suspend fun pruneOlderThan(cutoffEpochMillis: Long): Int =
        doseEventDao.deleteOlderThan(cutoffEpochMillis)
}

// ── Entity ↔ domain mapping ────────────────────────────────────────────

private const val KIND_DAILY = "DAILY"
private const val KIND_WEEKLY = "WEEKLY"
private const val KIND_ONE_OFF = "ONE_OFF"

private val DoseStatus.storageKey: String
    get() = when (this) {
        DoseStatus.Pending -> "PENDING"
        DoseStatus.Taken -> "TAKEN"
        DoseStatus.Snoozed -> "SNOOZED"
        // Not stored directly — derived from aged PENDING rows.
        DoseStatus.Missed -> "PENDING"
    }

private fun MedicationReminderEntity.toDomain(): MedicationReminder {
    val frequency = when (frequencyKind) {
        KIND_DAILY -> ReminderFrequency.Daily
        KIND_WEEKLY -> ReminderFrequency.WeeklyDays(daysOfWeekMask)
        KIND_ONE_OFF -> ReminderFrequency.OneOff(
            oneOffAtEpochMillis ?: error("ONE_OFF reminder missing oneOffAtEpochMillis"),
        )
        else -> {
            // Unknown discriminator usually means a future schema version
            // wrote a frequency kind this build doesn't understand. Log
            // loudly so a stale install doesn't silently coerce every
            // reminder to Daily without anyone noticing during QA.
            Log.w(
                "MedicationRepository",
                "Unknown frequencyKind='$frequencyKind' for reminder id=$id; defaulting to Daily",
            )
            ReminderFrequency.Daily
        }
    }
    return MedicationReminder(
        id = id,
        name = name,
        dosage = dosage,
        frequency = frequency,
        timeOfDayMinutes = timeOfDayMinutes,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

private fun MedicationReminder.toEntity(): MedicationReminderEntity {
    val kind = when (frequency) {
        ReminderFrequency.Daily -> KIND_DAILY
        is ReminderFrequency.WeeklyDays -> KIND_WEEKLY
        is ReminderFrequency.OneOff -> KIND_ONE_OFF
    }
    val mask = (frequency as? ReminderFrequency.WeeklyDays)?.daysMask ?: 0
    val oneOff = (frequency as? ReminderFrequency.OneOff)?.atEpochMillis
    return MedicationReminderEntity(
        id = id,
        name = name,
        dosage = dosage,
        frequencyKind = kind,
        timeOfDayMinutes = timeOfDayMinutes,
        daysOfWeekMask = mask,
        oneOffAtEpochMillis = oneOff,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

private fun DoseEventEntity.toDomain(now: Long): DoseEvent {
    val rawStatus = when (status) {
        "TAKEN" -> DoseStatus.Taken
        "SNOOZED" -> DoseStatus.Snoozed
        else -> DoseStatus.Pending
    }
    // Age out PENDING → Missed once the grace window has elapsed.
    val status = if (rawStatus == DoseStatus.Pending &&
        (now - scheduledAtEpochMillis) > MISSED_GRACE_MILLIS
    ) {
        DoseStatus.Missed
    } else {
        rawStatus
    }
    return DoseEvent(
        id = id,
        medicationId = medicationId,
        scheduledAtEpochMillis = scheduledAtEpochMillis,
        status = status,
        actedAtEpochMillis = actedAtEpochMillis,
    )
}

package com.example.mob_dev_portfolio.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mob_dev_portfolio.data.medication.MedicationReminder
import com.example.mob_dev_portfolio.data.medication.NextFireCalculator
import java.time.Instant

/**
 * AlarmManager-backed scheduler for medication reminders.
 *
 * Why AlarmManager (not WorkManager):
 *   WorkManager's minimum periodic interval is 15 minutes and its
 *   delivery has no wall-clock guarantee — a reminder that should
 *   fire at 08:00 could drift by tens of minutes. AlarmManager's
 *   `setExactAndAllowWhileIdle` delivers within NFR-MR-01's 60-second
 *   SLA even under Doze and is the Google-recommended primitive for
 *   "time-of-day notifications" use cases.
 *
 * Per-reminder scheduling:
 *   Each reminder has a stable request code (`reminderId.toInt()`).
 *   That one PendingIntent slot is reused across re-arm cycles — the
 *   `FLAG_UPDATE_CURRENT` on build ensures the extras always reflect
 *   the latest schedule, and cancellation needs to hit the exact same
 *   slot.
 *
 * Rearm-on-fire:
 *   AlarmManager alarms are inherently one-shot. `Daily` and
 *   `WeeklyDays` variants are rearmed by the receiver after it posts
 *   the notification; a `setRepeating` shortcut isn't used because it
 *   becomes inexact on API 19+ and can't respect a per-day mask.
 */
open class MedicationReminderScheduler(
    private val context: Context,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /**
     * Schedule (or reschedule) the next fire for [reminder]. Cancels
     * any pending alarm for the same id first so a re-schedule on an
     * edit doesn't leave the old fire-time live in the background.
     */
    open fun schedule(reminder: MedicationReminder, now: Instant = Instant.now()) {
        cancel(reminder.id)
        if (!reminder.enabled) return
        val next = NextFireCalculator.nextFire(reminder, now) ?: return
        setAlarm(reminder.id, next.toEpochMilli())
    }

    /**
     * Arm an explicit fire time — used by the receiver when it needs
     * to schedule a snooze exactly 15 minutes out regardless of the
     * reminder's recurrence rule.
     */
    open fun scheduleAt(reminderId: Long, triggerAtMillis: Long) {
        cancel(reminderId)
        setAlarm(reminderId, triggerAtMillis)
    }

    open fun cancel(reminderId: Long) {
        val pi = buildFirePendingIntent(reminderId, flags = PendingIntent.FLAG_NO_CREATE)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * Rearm every persisted reminder. Called from
     *   - [BootCompletedReceiver] after a device reboot (alarms don't
     *     survive a reboot, only the DB rows do),
     *   - [com.example.mob_dev_portfolio.AuraApplication.onCreate] on
     *     every cold start so a force-stopped process recovers on
     *     re-launch without waiting for the next boot.
     */
    open suspend fun rescheduleAll(reminders: List<MedicationReminder>, now: Instant = Instant.now()) {
        reminders.forEach { schedule(it, now) }
    }

    open fun cancelAll(reminderIds: List<Long>) {
        reminderIds.forEach(::cancel)
    }

    // ── internals ───────────────────────────────────────────────────

    private fun setAlarm(reminderId: Long, triggerAtMillis: Long) {
        val pi = buildFirePendingIntent(reminderId, flags = PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return
        try {
            // canScheduleExactAlarms() is the API 31+ runtime check for
            // the user-revocable SCHEDULE_EXACT_ALARM grant. minSdk on
            // this module is 31, so we can call it unconditionally —
            // the SDK_INT guard the lint flagged was dead code.
            //
            // Fall back to setAndAllowWhileIdle if the user has revoked
            // the grant; the 60-second SLA (NFR-MR-01) can still
            // usually be met under Doze for inexact alarms, and
            // degrading is preferable to a SecurityException that
            // drops the reminder entirely.
            if (!alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm denied for reminder $reminderId; falling back to inexact", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun buildFirePendingIntent(reminderId: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, MedicationReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or flags,
        )
    }

    companion object {
        private const val TAG = "MedReminderScheduler"
        const val ACTION_FIRE = "com.example.mob_dev_portfolio.action.MEDICATION_FIRE"
        const val EXTRA_REMINDER_ID = "com.example.mob_dev_portfolio.extra.REMINDER_ID"

        /** Snooze duration, per the acceptance criteria. */
        const val SNOOZE_MILLIS: Long = 15L * 60L * 1000L
    }
}

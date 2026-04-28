package com.example.mob_dev_portfolio.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.medication.ReminderFrequency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Fires when an alarm armed by [MedicationReminderScheduler] elapses.
 *
 * Responsibilities:
 *   1. Load the reminder that fired. Its row may have been deleted
 *      or disabled between scheduling and firing — both are treated
 *      as "do nothing" (the user already expressed the intent).
 *   2. Respect the global kill-switch in
 *      [com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository.medicationRemindersEnabled]
 *      (FR-MR-08). No notification, no history row, no rearm — but we
 *      keep the DB reminder itself so flipping the switch back on
 *      restores behaviour without data loss.
 *   3. Insert a `PENDING` dose event so the Taken/Snooze action
 *      buttons have an id to update (FR-MR-06 depends on this).
 *   4. Post the notification via [com.example.mob_dev_portfolio.notifications.MedicationReminderNotifier] with
 *      action buttons that carry the event id.
 *   5. Arm the next fire for recurring schedules. One-offs self-expire
 *      after firing once — [NextFireCalculator] returns null for a
 *      past-dated one-off, so `rescheduleAll` paths naturally skip it.
 *
 * Uses `goAsync()` so the DB round-trip can happen without blocking
 * the broadcast dispatcher. The 10-second budget the framework grants
 * is comfortably more than enough for an encrypted Room insert.
 */
class MedicationReminderReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MedicationReminderScheduler.ACTION_FIRE) return
        val reminderId = intent.getLongExtra(MedicationReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId <= 0L) return

        val pending = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, reminderId)
            } catch (t: Throwable) {
                Log.e(TAG, "Reminder fire handling failed for id=$reminderId", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(appContext: Context, reminderId: Long) {
        val container = (appContext as AuraApplication).container
        val globallyEnabled = container.uiPreferencesRepository
            .medicationRemindersEnabled.first()
        if (!globallyEnabled) {
            Log.i(TAG, "Global medication reminders disabled — skipping fire for id=$reminderId")
            return
        }

        val repo = container.medicationRepository
        val reminder = repo.getById(reminderId) ?: run {
            Log.i(TAG, "Reminder id=$reminderId no longer exists; nothing to fire")
            return
        }
        if (!reminder.enabled) {
            Log.i(TAG, "Reminder id=$reminderId is disabled; skipping fire")
            return
        }

        val now = Instant.now().toEpochMilli()
        val eventId = repo.recordFired(
            medicationId = reminder.id,
            scheduledAt = now,
            firedAt = now,
        )
        container.medicationReminderNotifier.notifyReminder(
            reminder = reminder,
            eventId = eventId,
        )

        // Rearm recurring schedules. One-offs naturally drop out: the
        // calculator returns null for a past-dated one-off so the
        // scheduler cancels without rearming.
        if (reminder.frequency !is ReminderFrequency.OneOff) {
            container.medicationReminderScheduler.schedule(reminder, Instant.now())
        }
    }

    companion object {
        private const val TAG = "MedReminderReceiver"
    }
}

package com.example.mob_dev_portfolio.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.mob_dev_portfolio.AuraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Handles Taken / Snooze action-button taps on an active reminder
 * notification.
 *
 * The PendingIntents carry two extras: the dose-event id to update
 * (so history reflects the action) and the reminder id (so snooze
 * knows which schedule to rearm, and the notification tag to cancel).
 *
 * Both actions always cancel the current notification — the Taken
 * path just dismisses it; the Snooze path rearms an alarm 15 minutes
 * out, which fires a *fresh* PENDING row and a *fresh* notification
 * at that point. Not mutating the original notification preserves the
 * append-only history model.
 */
class MedicationActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (eventId <= 0L || reminderId <= 0L) return

        val pending = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, action, eventId, reminderId)
            } catch (t: Throwable) {
                Log.e(TAG, "Action handling failed (action=$action)", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        appContext: Context,
        action: String,
        eventId: Long,
        reminderId: Long,
    ) {
        val container = (appContext as AuraApplication).container
        val repo = container.medicationRepository
        val now = Instant.now()

        when (action) {
            ACTION_TAKEN -> repo.markTaken(eventId, now.toEpochMilli())
            ACTION_SNOOZE -> {
                repo.markSnoozed(eventId, now.toEpochMilli())
                val triggerAt = now.toEpochMilli() + MedicationReminderScheduler.SNOOZE_MILLIS
                container.medicationReminderScheduler.scheduleAt(reminderId, triggerAt)
            }
            else -> return
        }

        // Always dismiss the notification the user just acted on.
        // Notification tag-space is keyed on reminder id so future fires
        // of the same reminder don't collide with a stale tray entry.
        NotificationManagerCompat.from(appContext).cancel(
            com.example.mob_dev_portfolio.notifications.MedicationReminderNotifier.notificationIdFor(reminderId),
        )
    }

    companion object {
        private const val TAG = "MedActionReceiver"

        const val ACTION_TAKEN = "com.example.mob_dev_portfolio.action.MED_TAKEN"
        const val ACTION_SNOOZE = "com.example.mob_dev_portfolio.action.MED_SNOOZE"
        const val EXTRA_EVENT_ID = "com.example.mob_dev_portfolio.extra.EVENT_ID"
        const val EXTRA_REMINDER_ID = "com.example.mob_dev_portfolio.extra.ACTION_REMINDER_ID"
    }
}

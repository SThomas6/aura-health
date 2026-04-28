package com.example.mob_dev_portfolio.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mob_dev_portfolio.MainActivity
import com.example.mob_dev_portfolio.R
import com.example.mob_dev_portfolio.data.medication.MedicationReminder
import com.example.mob_dev_portfolio.reminders.MedicationActionReceiver

/**
 * Builder + poster for medication-reminder notifications.
 *
 * Design decisions:
 *
 *  - **Separate channel from AI analysis** (`aura_medication_reminders`):
 *    users may want to silence analysis notifications while keeping
 *    reminders noisy, or vice versa. System channel settings is the
 *    right affordance for that.
 *
 *  - **High importance** (`IMPORTANCE_HIGH`): these are time-critical
 *    reminders where a missed dose matters. Heads-up display is the
 *    expected UX.
 *
 *  - **Notification id = reminder id**: collisions-by-design between
 *    consecutive fires of the same schedule; a second fire of the same
 *    reminder while the first is still in the tray *replaces* the
 *    first (rather than piling up). [notificationIdFor] is a pure
 *    derivation so the action receiver can cancel by the same key.
 *
 *  - **FLAG_IMMUTABLE** on every PendingIntent (NFR-MR-04). The extras
 *    captured at build time are exactly what the receiver runs with,
 *    which is the only safe contract for an API 31+ broadcast.
 *
 *  - **Post-permission check** mirrors [AnalysisNotifier.post] — if the
 *    user has revoked POST_NOTIFICATIONS (API 33+) we silently drop
 *    the post. Crashing the receiver on a permission the user has
 *    actively denied would turn a preference into a bug report.
 */
open class MedicationReminderNotifier(
    private val context: Context,
) {

    open fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = CHANNEL_DESCRIPTION }
        manager.createNotificationChannel(channel)
    }

    /**
     * Dismiss any tray entry for [reminderId]. Used by the in-app
     * Taken/Snooze buttons so acting inside the app matches the
     * behaviour of acting on the notification itself (the tray entry
     * goes away the moment the user expresses the intent, rather than
     * waiting for a fresh fire to replace it).
     */
    open fun cancelNotification(reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(reminderId))
    }

    open fun notifyReminder(reminder: MedicationReminder, eventId: Long) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val title = reminder.name
        val body = if (reminder.dosage.isBlank()) "Time to take your medication."
        else "Dose: ${reminder.dosage}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(buildOpenAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(
                R.mipmap.ic_launcher,
                "Taken",
                buildActionIntent(
                    action = MedicationActionReceiver.ACTION_TAKEN,
                    reminderId = reminder.id,
                    eventId = eventId,
                ),
            )
            .addAction(
                R.mipmap.ic_launcher,
                "Snooze 15 min",
                buildActionIntent(
                    action = MedicationActionReceiver.ACTION_SNOOZE,
                    reminderId = reminder.id,
                    eventId = eventId,
                ),
            )
            .build()

        NotificationManagerCompat.from(context).notify(
            notificationIdFor(reminder.id),
            notification,
        )
    }

    private fun buildOpenAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildActionIntent(action: String, reminderId: Long, eventId: Long): PendingIntent {
        val intent = Intent(context, MedicationActionReceiver::class.java).apply {
            this.action = action
            putExtra(MedicationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(MedicationActionReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        // Request code has to be unique per (reminder, action) pair so
        // the Taken and Snooze PendingIntents don't alias each other.
        // The high-bit partition keeps notifier request codes clear of
        // the scheduler's (which use the raw reminder id).
        val requestCode = when (action) {
            MedicationActionReceiver.ACTION_TAKEN -> TAKEN_REQUEST_BASE + reminderId.toInt()
            else -> SNOOZE_REQUEST_BASE + reminderId.toInt()
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "aura_medication_reminders"
        const val CHANNEL_NAME = "Medication reminders"
        const val CHANNEL_DESCRIPTION =
            "Time-of-day notifications for the medications you've asked the app to remind you about."

        private const val OPEN_APP_REQUEST_CODE = 3001
        private const val TAKEN_REQUEST_BASE = 100_000
        private const val SNOOZE_REQUEST_BASE = 200_000
        private const val NOTIFICATION_ID_BASE = 10_000

        /**
         * Stable notification-id derivation from reminder id, exposed
         * so the action receiver can cancel by the same key. Offsetting
         * keeps us clear of [AnalysisNotifier.NOTIFICATION_ID_RESULT].
         */
        fun notificationIdFor(reminderId: Long): Int =
            NOTIFICATION_ID_BASE + reminderId.toInt()
    }
}

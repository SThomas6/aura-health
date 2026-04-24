package com.example.mob_dev_portfolio.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mob_dev_portfolio.AuraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms every persisted medication reminder after a device reboot.
 *
 * AlarmManager drops every alarm on shutdown. Without this receiver a
 * user who reboots their phone would silently stop getting reminders
 * until the next time they opened the app — which is exactly the
 * failure mode FR-MR-07 prohibits.
 *
 * The manifest filter also includes `ACTION_LOCKED_BOOT_COMPLETED` so
 * reminders rearm as early as possible on devices with a direct-boot
 * lock screen; we read from DataStore + Room after user unlock only,
 * but the Application's `onCreate` will have bootstrapped by the time
 * this receiver runs.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        val pending = goAsync()
        scope.launch {
            try {
                val container = (context.applicationContext as AuraApplication).container
                val reminders = container.medicationRepository.listAll()
                container.medicationReminderScheduler.rescheduleAll(reminders)
                Log.i(TAG, "Rearmed ${reminders.size} medication reminders after $action")
            } catch (t: Throwable) {
                Log.e(TAG, "Rearm after $action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MedBootReceiver"
    }
}

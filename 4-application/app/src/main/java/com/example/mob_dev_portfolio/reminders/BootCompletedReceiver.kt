package com.example.mob_dev_portfolio.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
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
 * lock screen. **However**, our DataStore and Room/SQLCipher are NOT
 * `directBootAware` — they live in credential-encrypted storage which
 * is unreadable until the user has unlocked the device for the first
 * time after boot. Touching them pre-unlock throws.
 *
 * So when `LOCKED_BOOT_COMPLETED` fires, we early-return without doing
 * any DB work. The follow-up `BOOT_COMPLETED` broadcast (which fires
 * once the user unlocks) does the actual rearm. This trades a tiny
 * window of un-armed reminders (between boot and first unlock) for not
 * silently losing them entirely on FBE devices, which is what happened
 * before this guard.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        // Direct-boot guard. On FBE devices (every modern Android),
        // credential-encrypted storage — which is where our DataStore
        // and SQLCipher database live — isn't readable until the user
        // unlocks for the first time after boot. Attempting any DB
        // work before that will fail noisily. Defer to the follow-up
        // BOOT_COMPLETED broadcast, which fires post-unlock.
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager?.isUserUnlocked == false) {
            Log.i(TAG, "Skipping rearm on $action — user not unlocked yet")
            return
        }

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

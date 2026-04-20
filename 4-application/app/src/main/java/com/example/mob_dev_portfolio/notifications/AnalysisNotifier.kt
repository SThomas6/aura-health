package com.example.mob_dev_portfolio.notifications

import android.Manifest
import android.app.Notification
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
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance

/**
 * Centralises everything the background worker needs to surface an analysis
 * outcome to the user:
 *   - one-shot [NotificationChannel] registration (idempotent),
 *   - a "tap to view result" deep-link [PendingIntent] built with
 *     [PendingIntent.FLAG_IMMUTABLE] — required on API 31+ and explicitly
 *     called out by the story's technical guidelines,
 *   - the success and failure notifications themselves.
 *
 * Exposed as an `open` class so tests (and, later, feature flags) can swap
 * in a double without touching WorkManager. Keeping the builder helpers
 * public lets us unit-test the intent shape in isolation.
 */
open class AnalysisNotifier(
    private val context: Context,
) {

    /**
     * Creates (or refreshes) the notification channel. Safe to call
     * multiple times — `createNotificationChannel` is documented to ignore
     * duplicates with the same ID. We re-create on every post rather than
     * tracking state in memory, so a process kill between app launch and
     * the first notification can't leave us channel-less.
     */
    open fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The happy path: the worker got a [AnalysisGuidance] back from the
     * model. The headline mirrors the enum copy so QA can diff against the
     * acceptance-criterion wording directly.
     *
     * [runId] is the Room rowId of the just-inserted analysis run; the
     * deep-link carries it so a notification tap lands on the specific
     * run's detail view rather than the generic history list. Caller may
     * pass `null` only for legacy callers that haven't wired the repo in
     * yet — the deep-link falls back to the history screen in that case.
     */
    open fun notifySuccess(guidance: AnalysisGuidance, runId: Long? = null) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(guidance.headline)
            .setContentText("Your AI analysis is ready — tap to view the full result.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(guidance.bodyHint))
            .setContentIntent(buildDeepLink(runId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(NOTIFICATION_ID_RESULT, notification)
    }

    /**
     * Failure path — covers the NFRs "gracefully time out after 1 minute"
     * and "handle API response errors smoothly". We deliberately do NOT
     * swallow the failure silently: the story says the user should be
     * "notified that the analysis could not be completed".
     */
    open fun notifyFailure(reason: FailureReason) {
        val (title, body) = when (reason) {
            FailureReason.Timeout -> "Analysis took too long" to
                "AI analysis didn't finish within a minute — tap to try again."
            FailureReason.NoNetwork -> "No internet for analysis" to
                "We lost connection while analysing — tap to try again."
            is FailureReason.ApiError -> "Analysis couldn't finish" to
                "The AI service returned an error: ${reason.message}. Tap to try again."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // Failure has no run to open — the deep-link lands on the
            // history screen, where the user can tap "Run AI analysis"
            // again.
            .setContentIntent(buildDeepLink(runId = null))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        post(NOTIFICATION_ID_RESULT, notification)
    }

    /**
     * Builds the PendingIntent that re-launches [MainActivity] and carries
     * an extra marker telling the UI to open the Analysis destination.
     *
     * Flag choices, deliberately:
     *   - [PendingIntent.FLAG_IMMUTABLE] — required on API 31+ and required
     *     by the story. An immutable intent can't have its extras rewritten
     *     after creation, which is the security property we want.
     *   - [PendingIntent.FLAG_UPDATE_CURRENT] — if a previous notification's
     *     PendingIntent is still live the system replaces its extras with
     *     ours, so the "tap the latest notification" case always opens the
     *     latest result.
     */
    fun buildDeepLink(runId: Long? = null): PendingIntent {
        val intent = buildDeepLinkIntent(context, runId)
        return PendingIntent.getActivity(
            context,
            DEEP_LINK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Posts the notification, respecting API 33's runtime permission.
     *
     * If the user has denied POST_NOTIFICATIONS — or if the channel has
     * been blocked from Settings, which the story calls out as the intended
     * opt-out mechanism — we silently drop the post. Crashing or retrying
     * would be worse: the user has already told the OS they don't want
     * these.
     */
    private fun post(id: Int, notification: Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    sealed interface FailureReason {
        data object Timeout : FailureReason
        data object NoNetwork : FailureReason
        data class ApiError(val message: String) : FailureReason
    }

    companion object {
        const val CHANNEL_ID = "aura_analysis_results"
        const val CHANNEL_NAME = "AI analysis results"
        const val CHANNEL_DESCRIPTION =
            "Tells you when a background AI health analysis has finished running."

        /** Single ID — a new result replaces any stale one in the tray. */
        const val NOTIFICATION_ID_RESULT = 1001

        /** Unique request code so our PendingIntent doesn't collide with a future one. */
        const val DEEP_LINK_REQUEST_CODE = 2001

        /**
         * Extra flag read by [MainActivity] to route a fresh launch
         * directly to the Analysis destination. Namespaced so it can't
         * clash with an OS-reserved extra.
         */
        const val EXTRA_OPEN_ANALYSIS_RESULT =
            "com.example.mob_dev_portfolio.EXTRA_OPEN_ANALYSIS_RESULT"

        /**
         * Companion extra carrying the Room rowId of the run to open. Zero
         * or missing means "no specific run, land on the history screen".
         * Namespaced to avoid colliding with any OS-reserved extra.
         */
        const val EXTRA_ANALYSIS_RUN_ID =
            "com.example.mob_dev_portfolio.EXTRA_ANALYSIS_RUN_ID"

        /**
         * Factory exposed so [AnalysisNotifierTest] can assert the intent
         * shape without a live Context-backed PendingIntent.
         */
        fun buildDeepLinkIntent(context: Context, runId: Long? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_ANALYSIS_RESULT, true)
                if (runId != null && runId > 0L) {
                    putExtra(EXTRA_ANALYSIS_RUN_ID, runId)
                }
            }
    }
}

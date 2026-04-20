package com.example.mob_dev_portfolio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.mob_dev_portfolio.AppContainer
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.notifications.AnalysisNotifier
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Runs the Gemini analysis in a WorkManager-managed coroutine so the call
 * completes even if the user backgrounds or kills the app mid-request.
 *
 * Why a [CoroutineWorker] and not a ForegroundService?
 *   - The work is short (capped at [TIMEOUT_MILLIS], i.e. 1 minute) — a
 *     foreground service would need a sticky "analysing…" notification for
 *     the whole window, which is worse UX than one notification at the
 *     end.
 *   - WorkManager still schedules a deferred retry if the OS kills the
 *     process, which is the "survive memory pressure" NFR.
 *
 * Why does the timeout live here and not in OkHttp?
 *   - OkHttp's `callTimeout` bounds the socket round-trip. The story asks
 *     for the *analysis process* to halt — that includes profile/log
 *     loading, sanitization, prompt building, and any local parsing.
 *     [withTimeout] wraps the whole pipeline so we can't drift over the
 *     minute because of, say, a slow DataStore read.
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AuraApplication).container
        val notifier = container.analysisNotifier
        notifier.ensureChannel() // idempotent — guarantees the channel exists before we post.

        val userContext = inputData.getString(KEY_USER_CONTEXT).orEmpty()

        // Second-guess connectivity at the start of work. The user's network
        // state may have changed since the ViewModel enqueued us; re-checking
        // here avoids an unnecessary OkHttp attempt and gives us a cleaner
        // "no network" notification than a socket-level UnknownHostException
        // bubbling up.
        if (!container.networkConnectivity.isOnline()) {
            notifier.notifyFailure(AnalysisNotifier.FailureReason.NoNetwork)
            return Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_NO_NETWORK).build(),
            )
        }

        return try {
            val profile = runCatching { container.userProfileRepository.profile.first() }
                .getOrDefault(UserProfile())
            val logs: List<SymptomLog> = runCatching {
                container.symptomLogRepository.observeAll().first()
            }.getOrDefault(emptyList())

            val outcome = withTimeout(TIMEOUT_MILLIS) {
                container.analysisService.analyze(profile, userContext, logs)
            }

            handleOutcome(container, notifier, outcome, logs)
        } catch (_: TimeoutCancellationException) {
            // withTimeout translates to a cancellation we can catch here;
            // the coroutine is already cancelled at this point so no more
            // work happens. We still need to notify the user.
            notifier.notifyFailure(AnalysisNotifier.FailureReason.Timeout)
            Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_TIMEOUT).build(),
            )
        } catch (error: Exception) {
            // A last-resort catch so any unexpected throwable becomes a
            // graceful failure notification rather than a WorkManager retry
            // storm. We exclude CancellationException propagation by
            // rethrowing it first — otherwise WorkManager's own cancellation
            // support breaks.
            if (error is kotlinx.coroutines.CancellationException) throw error
            notifier.notifyFailure(
                AnalysisNotifier.FailureReason.ApiError(error.message ?: "Unexpected failure"),
            )
            Result.failure(
                Data.Builder()
                    .putString(KEY_FAILURE_KIND, FAILURE_API_ERROR)
                    .putString(KEY_FAILURE_MESSAGE, error.message ?: "Unexpected failure")
                    .build(),
            )
        }
    }

    private suspend fun handleOutcome(
        container: AppContainer,
        notifier: AnalysisNotifier,
        outcome: AnalysisResult,
        logs: List<SymptomLog>,
    ): Result = when (outcome) {
        is AnalysisResult.Success -> {
            val guidance = AnalysisGuidance.fromSummary(
                summary = outcome.summaryText,
                maxSeverityInRecentLogs = logs.maxOfOrNull { it.severity } ?: 0,
            )
            val completedAt = System.currentTimeMillis()

            // Room — durable, queryable, and the source of truth for the
            // history list. Inserted FIRST so the notification deep-link
            // below can carry the real rowId: if the write throws we bail
            // out before notifying and the user retries rather than being
            // told a run succeeded that has no row to open.
            val runId = container.analysisHistoryRepository.recordRun(
                summaryText = outcome.summaryText,
                guidance = guidance,
                completedAtEpochMillis = completedAt,
                logIds = logs.map { it.id },
            )

            // DataStore — one-slot cache of the latest successful run, kept
            // for legacy callers (the existing AnalysisScreen still reads
            // it) and for the fast path on cold start. A future cleanup
            // pass can drop this once every reader has moved to the Room
            // flow, but for now both stores agree on the same summary.
            container.analysisResultStore.save(
                summaryText = outcome.summaryText,
                guidance = guidance,
                completedAtEpochMillis = completedAt,
            )

            notifier.notifySuccess(guidance = guidance, runId = runId)
            Result.success(
                Data.Builder()
                    .putString(KEY_GUIDANCE, guidance.name)
                    .putLong(KEY_RUN_ID, runId)
                    .build(),
            )
        }
        AnalysisResult.NoNetwork -> {
            notifier.notifyFailure(AnalysisNotifier.FailureReason.NoNetwork)
            Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_NO_NETWORK).build(),
            )
        }
        AnalysisResult.Timeout -> {
            notifier.notifyFailure(AnalysisNotifier.FailureReason.Timeout)
            Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_TIMEOUT).build(),
            )
        }
        is AnalysisResult.ApiError -> {
            notifier.notifyFailure(AnalysisNotifier.FailureReason.ApiError(outcome.message))
            Result.failure(
                Data.Builder()
                    .putString(KEY_FAILURE_KIND, FAILURE_API_ERROR)
                    .putString(KEY_FAILURE_MESSAGE, outcome.message)
                    .build(),
            )
        }
    }

    companion object {
        /** The free-text the user typed on the Analysis screen. */
        const val KEY_USER_CONTEXT = "user_context"

        /** Success output — the guidance enum name. */
        const val KEY_GUIDANCE = "guidance"

        /**
         * Success output — the Room rowId of the freshly inserted run. The
         * ViewModel can use this to jump straight to the detail screen; in
         * practice today we only use it to build the notification
         * deep-link, but plumbing it through [Result.success] keeps parity
         * with the failure-kind pattern and costs us nothing.
         */
        const val KEY_RUN_ID = "run_id"

        /**
         * Failure outputs. The ViewModel reads these to paint a matching
         * transient-error snackbar when the worker finishes while the UI
         * is in the foreground. They mirror the notification copy.
         */
        const val KEY_FAILURE_KIND = "failure_kind"
        const val KEY_FAILURE_MESSAGE = "failure_message"

        const val FAILURE_TIMEOUT = "timeout"
        const val FAILURE_NO_NETWORK = "no_network"
        const val FAILURE_API_ERROR = "api_error"

        /**
         * Hard cap on the whole analysis — the story's NFR-PE-02. One
         * minute is long enough for Gemini's p99 latency on our prompt
         * size, short enough that a user who tapped and walked off doesn't
         * come back to a dead screen.
         */
        const val TIMEOUT_MILLIS: Long = 60_000L

        /**
         * Unique work name. `ExistingWorkPolicy.REPLACE` uses this to
         * cancel any in-flight analysis when the user taps "Run AI
         * analysis" a second time — we only ever want one outstanding.
         */
        const val UNIQUE_WORK_NAME: String = "aura_analysis_once"
    }
}

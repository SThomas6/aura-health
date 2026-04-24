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
import com.example.mob_dev_portfolio.data.doctor.DoctorContextSnapshot
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthSnapshot
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.notifications.AnalysisNotifier
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant

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
        // Background/scheduled runs pass KEY_SCHEDULED = true. We use this
        // later to keep "all clear" results silent — the user only asked to
        // be pinged when something needs attention.
        val isScheduled = inputData.getBoolean(KEY_SCHEDULED, false)

        // Second-guess connectivity at the start of work. The user's network
        // state may have changed since the ViewModel enqueued us; re-checking
        // here avoids an unnecessary OkHttp attempt and gives us a cleaner
        // "no network" notification than a socket-level UnknownHostException
        // bubbling up.
        if (!container.networkConnectivity.isOnline()) {
            // Scheduled runs are invisible on purpose — no user was waiting
            // for a result, so a "couldn't reach the server" notification
            // at 3am would just be noise. WorkManager will retry on the
            // next weekly tick when connectivity is back.
            if (!isScheduled) {
                notifier.notifyFailure(AnalysisNotifier.FailureReason.NoNetwork)
            }
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

            // Health Connect snapshot — best-effort. A failure here must
            // NOT block the analysis: if the SDK throws or the user hasn't
            // granted anything, we fall back to Empty and the prompt runs
            // without health context, which matches the story's
            // "graceful degradation" acceptance criterion.
            val healthSnapshot = runCatching {
                // Two gates: the user must both be connected AND have
                // opted in to AI inclusion. A connected user who flips
                // the AI toggle off still gets their dashboard but their
                // readings stay out of the prompt.
                val connectionOn = container.healthPreferencesRepository
                    .connectionActive.first()
                val integrationOn = container.healthPreferencesRepository
                    .integrationEnabled.first()
                if (!connectionOn || !integrationOn) return@runCatching HealthSnapshot.Empty
                val enabled = container.healthPreferencesRepository
                    .enabledMetrics.first()
                if (enabled.isEmpty()) return@runCatching HealthSnapshot.Empty
                container.healthConnectService.buildSnapshot(
                    enabledMetrics = enabled,
                    logStartTimes = logs.associate {
                        it.id to Instant.ofEpochMilli(it.startEpochMillis)
                    },
                )
            }.getOrDefault(HealthSnapshot.Empty)

            // Doctor-visits snapshot — cleared logs get filtered out of the
            // prompt entirely, diagnosis-linked logs get annotated. Failure
            // here is soft (empty snapshot) so a broken doctor-data path
            // can never wedge the analysis pipeline.
            val doctorContext = runCatching {
                container.doctorVisitRepository.snapshotForAnalysis()
            }.getOrDefault(DoctorContextSnapshot.Empty)

            val outcome = withTimeout(TIMEOUT_MILLIS) {
                container.analysisService.analyze(
                    profile = profile,
                    userContext = userContext,
                    logs = logs,
                    healthSnapshot = healthSnapshot,
                    doctorContext = doctorContext,
                )
            }

            handleOutcome(container, notifier, outcome, logs, healthSnapshot, isScheduled)
        } catch (_: TimeoutCancellationException) {
            // withTimeout translates to a cancellation we can catch here;
            // the coroutine is already cancelled at this point so no more
            // work happens. We still need to notify the user — unless this
            // was a background scheduled run, in which case silence.
            if (!isScheduled) {
                notifier.notifyFailure(AnalysisNotifier.FailureReason.Timeout)
            }
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
            if (!isScheduled) {
                notifier.notifyFailure(
                    AnalysisNotifier.FailureReason.ApiError(error.message ?: "Unexpected failure"),
                )
            }
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
        healthSnapshot: HealthSnapshot,
        isScheduled: Boolean,
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
            //
            // The health-metrics list is persisted as a lightweight CSV
            // (see [AnalysisRunEntity.healthMetricsCsv]) so the detail
            // screen can surface "the AI considered: Steps, Sleep…"
            // without re-hitting Health Connect. Passing null here would
            // indicate a pre-migration row; we want the explicit empty
            // list to mean "integration active, nothing readable".
            val runId = container.analysisHistoryRepository.recordRun(
                summaryText = outcome.summaryText,
                guidance = guidance,
                completedAtEpochMillis = completedAt,
                logIds = logs.map { it.id },
                healthMetricsShortLabels = healthSnapshot.shortLabelList(),
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

            // Notification gating.
            //   - Manual run: always notify — the user explicitly asked for
            //     an analysis and expects a ping when it's done, whether
            //     the verdict is Clear or SeekAdvice.
            //   - Scheduled run: only notify when the result is SeekAdvice.
            //     The user's explicit ask was "if it's in the all clear it
            //     shouldn't give a notification", so a Clear background run
            //     is persisted silently to history and the detail can be
            //     browsed later if the user ever wants to.
            val shouldNotify = !isScheduled || guidance == AnalysisGuidance.SeekAdvice
            if (shouldNotify) {
                notifier.notifySuccess(guidance = guidance, runId = runId)
            }
            Result.success(
                Data.Builder()
                    .putString(KEY_GUIDANCE, guidance.name)
                    .putLong(KEY_RUN_ID, runId)
                    .build(),
            )
        }
        AnalysisResult.NoNetwork -> {
            if (!isScheduled) notifier.notifyFailure(AnalysisNotifier.FailureReason.NoNetwork)
            Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_NO_NETWORK).build(),
            )
        }
        AnalysisResult.Timeout -> {
            if (!isScheduled) notifier.notifyFailure(AnalysisNotifier.FailureReason.Timeout)
            Result.failure(
                Data.Builder().putString(KEY_FAILURE_KIND, FAILURE_TIMEOUT).build(),
            )
        }
        is AnalysisResult.ApiError -> {
            if (!isScheduled) notifier.notifyFailure(AnalysisNotifier.FailureReason.ApiError(outcome.message))
            Result.failure(
                Data.Builder()
                    .putString(KEY_FAILURE_KIND, FAILURE_API_ERROR)
                    .putString(KEY_FAILURE_MESSAGE, outcome.message)
                    .build(),
            )
        }
    }

    /**
     * Persist-friendly projection of which Health Connect metrics were
     * readable at analysis time. Returns null when the snapshot is
     * [HealthSnapshot.Empty] (no integration, no grants, SDK missing)
     * so the Room row stays null and the UI can tell "integration
     * wasn't active" apart from "integration active, no data found".
     */
    private fun HealthSnapshot.shortLabelList(): List<String>? {
        if (this === HealthSnapshot.Empty) return null
        if (includedMetrics.isEmpty() && aggregate7Day.isEmpty) return null
        return includedMetrics
            .sortedBy { it.ordinal }
            .map(HealthConnectMetric::shortLabel)
    }

    companion object {
        /** The free-text the user typed on the Analysis screen. */
        const val KEY_USER_CONTEXT = "user_context"

        /**
         * Marks a run as coming from the weekly background schedule rather
         * than a user tap. Scheduled runs suppress notifications unless the
         * result is [AnalysisGuidance.SeekAdvice], per the user's explicit
         * "only notify if there's a problem" requirement.
         */
        const val KEY_SCHEDULED = "scheduled"

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

        /**
         * Unique work name for the recurring weekly background analysis.
         * Kept separate from [UNIQUE_WORK_NAME] so a manual "run now" tap
         * (REPLACE policy) never cancels the scheduled cadence, and vice
         * versa — they're independent lifecycles that both belong to the
         * same worker class.
         */
        const val UNIQUE_WEEKLY_NAME: String = "aura_analysis_weekly"
    }
}

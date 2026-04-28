package com.example.mob_dev_portfolio.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

/**
 * Thin façade over [WorkManager] for the Analysis feature.
 *
 * Two reasons to have this rather than call WorkManager inline from the
 * ViewModel:
 *   1. **Testability** — the interface is the seam the ViewModel unit test
 *      substitutes a fake at, so we never need to stand up WorkManager's
 *      content-provider-driven init in a pure JVM test.
 *   2. **Encapsulation of constraints** — the unique-work name, the
 *      [NetworkType.CONNECTED] requirement, and the [ExistingWorkPolicy]
 *      all live in one place, so a future change (e.g. switching to
 *      KEEP so double-taps ignore rather than replace) touches one file.
 */
interface AnalysisScheduler {
    fun enqueue(userContext: String)
    fun currentWorkInfos(): Flow<List<WorkInfo>>

    /**
     * Ensure a weekly background analysis is scheduled.
     *
     * Idempotent: call this from `AuraApplication.onCreate` on every cold
     * start. [ExistingPeriodicWorkPolicy.KEEP] means the first call wins —
     * we never restart the countdown, so the weekly cadence drifts at most
     * one period even if the user force-stops and relaunches the app.
     *
     * The worker receives `KEY_SCHEDULED = true` so it knows to suppress
     * the "you look in the clear" notification — the user only wants a
     * ping when something actually needs attention.
     */
    fun scheduleWeekly()
}

class WorkManagerAnalysisScheduler(
    private val workManager: WorkManager,
) : AnalysisScheduler {

    override fun enqueue(userContext: String) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_USER_CONTEXT to userContext))
            .setConstraints(
                // Still require a live network — if the device goes offline
                // before the worker starts, WorkManager holds the request
                // and only kicks it off once connectivity returns, which is
                // more forgiving than failing hard on a transient dropout.
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            AnalysisWorker.UNIQUE_WORK_NAME,
            // REPLACE: a second tap cancels the in-flight run and starts
            // a fresh one with the new userContext. Users who re-tap usually
            // mean "I changed my mind about the context field".
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun currentWorkInfos(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(AnalysisWorker.UNIQUE_WORK_NAME)

    override fun scheduleWeekly() {
        val request = PeriodicWorkRequestBuilder<AnalysisWorker>(7, TimeUnit.DAYS)
            .setInputData(workDataOf(AnalysisWorker.KEY_SCHEDULED to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // Gentle initial delay so a cold start that schedules us doesn't
            // also immediately fire a run while the user is still mid-launch.
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            AnalysisWorker.UNIQUE_WEEKLY_NAME,
            // KEEP: once the weekly cadence is established, we don't want
            // every cold start to reset the timer. Only a ConfigurationChange
            // (new period / new constraints) would warrant UPDATE, which we
            // don't currently need.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

}

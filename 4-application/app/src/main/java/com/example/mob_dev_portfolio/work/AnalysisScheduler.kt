package com.example.mob_dev_portfolio.work

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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
    fun cancel()
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

    override fun cancel() {
        workManager.cancelUniqueWork(AnalysisWorker.UNIQUE_WORK_NAME)
    }
}

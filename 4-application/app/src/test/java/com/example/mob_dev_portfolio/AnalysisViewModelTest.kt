package com.example.mob_dev_portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.work.Data
import androidx.work.WorkInfo
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisResultStore
import com.example.mob_dev_portfolio.data.ai.StoredAnalysis
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import com.example.mob_dev_portfolio.ui.analysis.AnalysisPhase
import com.example.mob_dev_portfolio.ui.analysis.AnalysisViewModel
import com.example.mob_dev_portfolio.work.AnalysisScheduler
import com.example.mob_dev_portfolio.work.AnalysisWorker
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the ViewModel contract for the Analysis screen after the switch to
 * WorkManager.
 *
 * Two tests matter most for the background-ai-processing story:
 *   - [offline_tap_does_not_enqueue_work] — pins the "offline guard runs
 *     before any WorkManager activity" behaviour. The scheduler's enqueue
 *     counter must stay at zero, mirroring the earlier acceptance criterion
 *     but against the new seam.
 *   - [failed_work_surfaces_transient_error] — verifies that a failure
 *     flowing back via WorkInfo.outputData is decoded into the friendly
 *     snackbar copy a user sees while the screen is in the foreground.
 *
 * The happy "work succeeds, result appears" path is also covered: we feed
 * the store a [StoredAnalysis] (as the worker would) and assert the phase
 * moves to [AnalysisPhase.Success].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun offline_tap_does_not_enqueue_work() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { false },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()

        // Most important: WorkManager was never touched.
        assertEquals(0, scheduler.enqueueCount)
        assertEquals(AnalysisPhase.Idle, vm.state.value.phase)
        assertEquals("No internet — connect and try again.", vm.state.value.transientError)
    }

    @Test
    fun online_tap_enqueues_and_flips_to_loading() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onUserContextChange("felt off today")
        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, scheduler.enqueueCount)
        assertEquals("felt off today", scheduler.lastUserContext)
        assertTrue(
            "expected Loading after tap, saw ${vm.state.value.phase}",
            vm.state.value.phase is AnalysisPhase.Loading,
        )
    }

    @Test
    fun stored_result_is_reflected_as_success_phase() = runTest(testDispatcher) {
        val store = FakeResultStore()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = store,
            scheduler = RecordingScheduler(),
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        store.emit(
            StoredAnalysis(
                summaryText = "GUIDANCE: clear\n## Patterns\n- ok",
                guidance = AnalysisGuidance.Clear,
                completedAtEpochMillis = 1_000L,
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val phase = vm.state.value.phase
        assertTrue("expected Success, got $phase", phase is AnalysisPhase.Success)
        val success = phase as AnalysisPhase.Success
        assertEquals(AnalysisGuidance.Clear, success.guidance)
        assertTrue(success.summaryText.contains("Patterns"))
    }

    /**
     * Pins the "fresh-start" contract on the analysis surface: when the
     * VM comes up and the result store already carries a prior run from
     * a previous session, the screen must open in [AnalysisPhase.Idle].
     * If it didn't, the user would tap into "Run AI analysis" and see
     * last week's summary painted on top of the form — confusing the
     * "kick off a new run" surface with the history view.
     *
     * The VM achieves this with `drop(1)` on the result-store flow:
     * the cached emission at subscription time is skipped, while
     * subsequent emissions (the worker-just-finished case covered by
     * [stored_result_is_reflected_as_success_phase]) still flow
     * through.
     */
    @Test
    fun cached_prior_result_does_not_paint_success_on_screen_open() = runTest(testDispatcher) {
        val store = FakeResultStore().apply {
            emit(
                StoredAnalysis(
                    summaryText = "stale summary from last week",
                    guidance = AnalysisGuidance.SeekAdvice,
                    completedAtEpochMillis = 0L,
                ),
            )
        }
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = store,
            scheduler = RecordingScheduler(),
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "screen should open Idle even with a cached prior result",
            AnalysisPhase.Idle,
            vm.state.value.phase,
        )
    }

    @Test
    fun failed_work_surfaces_transient_error() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.phase is AnalysisPhase.Loading)

        scheduler.emit(
            fakeWorkInfo(
                state = WorkInfo.State.FAILED,
                outputData = Data.Builder()
                    .putString(AnalysisWorker.KEY_FAILURE_KIND, AnalysisWorker.FAILURE_TIMEOUT)
                    .build(),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AnalysisPhase.Idle, vm.state.value.phase)
        assertEquals("AI took too long to respond — try again.", vm.state.value.transientError)
    }

    @Test
    fun api_error_failure_includes_server_message() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()

        scheduler.emit(
            fakeWorkInfo(
                state = WorkInfo.State.FAILED,
                outputData = Data.Builder()
                    .putString(AnalysisWorker.KEY_FAILURE_KIND, AnalysisWorker.FAILURE_API_ERROR)
                    .putString(AnalysisWorker.KEY_FAILURE_MESSAGE, "quota exceeded")
                    .build(),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("quota exceeded", vm.state.value.transientError)
    }

    @Test
    fun double_tap_while_loading_does_not_fire_twice() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.phase is AnalysisPhase.Loading)

        vm.triggerAnalysis() // second tap while still loading
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, scheduler.enqueueCount)
    }

    @Test
    fun transient_error_shown_callback_clears_state() = runTest(testDispatcher) {
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = RecordingScheduler(),
            connectivity = { false },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.transientError)

        vm.onTransientErrorShown()
        assertNull(vm.state.value.transientError)
    }

    @Test
    fun no_network_failure_from_worker_is_mapped_to_friendly_copy() = runTest(testDispatcher) {
        val scheduler = RecordingScheduler()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            resultStore = FakeResultStore(),
            scheduler = scheduler,
            connectivity = { true },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()
        scheduler.emit(
            fakeWorkInfo(
                state = WorkInfo.State.FAILED,
                outputData = Data.Builder()
                    .putString(AnalysisWorker.KEY_FAILURE_KIND, AnalysisWorker.FAILURE_NO_NETWORK)
                    .build(),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val err = vm.state.value.transientError
        assertNotNull(err)
        // Distinct from the pre-tap offline copy — lets QA tell the two apart.
        assertFalse(err!!.startsWith("No internet —"))
    }

    // --- test doubles ---------------------------------------------------

    private class FakeProfileRepository(
        initial: UserProfile = UserProfile(),
    ) : UserProfileRepository(dataStore = UnusedDataStore) {
        private val flow = MutableStateFlow(initial)
        override val profile: Flow<UserProfile> get() = flow.asStateFlow()
        override suspend fun setFullName(name: String?) {
            flow.value = flow.value.copy(fullName = name?.takeIf { it.isNotBlank() })
        }
        override suspend fun setDateOfBirth(dobMillis: Long?) {
            flow.value = flow.value.copy(dateOfBirthEpochMillis = dobMillis)
        }
    }

    /**
     * The real [AnalysisResultStore] requires a DataStore backing; we
     * override the observable `latest` so the ViewModel watches our
     * MutableStateFlow instead. save()/clear() are no-ops here — we test
     * them separately against a real store.
     */
    private class FakeResultStore : AnalysisResultStore(dataStore = UnusedDataStore) {
        private val flow = MutableStateFlow<StoredAnalysis?>(null)
        override val latest: Flow<StoredAnalysis?> = flow.asStateFlow()

        fun emit(value: StoredAnalysis?) {
            flow.value = value
        }

        override suspend fun save(
            summaryText: String,
            guidance: AnalysisGuidance,
            completedAtEpochMillis: Long,
        ) {
            flow.value = StoredAnalysis(summaryText, guidance, completedAtEpochMillis)
        }

        override suspend fun clear() {
            flow.value = null
        }
    }

    private class RecordingScheduler : AnalysisScheduler {
        var enqueueCount: Int = 0
            private set
        var lastUserContext: String? = null
            private set
        private val flow = MutableStateFlow<List<WorkInfo>>(emptyList())

        override fun enqueue(userContext: String) {
            enqueueCount += 1
            lastUserContext = userContext
            emit(
                fakeWorkInfo(
                    state = WorkInfo.State.RUNNING,
                    outputData = Data.EMPTY,
                ),
            )
        }

        override fun currentWorkInfos(): Flow<List<WorkInfo>> = flow.asStateFlow()
        // The weekly schedule surface is outside the ViewModel's concerns,
        // so the recording fake just swallows the call.
        override fun scheduleWeekly() = Unit

        fun emit(info: WorkInfo) {
            flow.value = listOf(info)
        }
    }

    private object UnusedDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableStateFlow(mutablePreferencesOf())
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = error("UnusedDataStore.updateData should not be called")
    }
}

/**
 * work-runtime 2.9 exposes a 7-arg constructor for [WorkInfo]; we use it
 * to build fakes without pulling in TestListenableWorkerBuilder (which
 * would expect a real Worker class). Kept file-private so the inner test
 * classes can reach it.
 */
private fun fakeWorkInfo(
    state: WorkInfo.State,
    outputData: Data = Data.EMPTY,
): WorkInfo = WorkInfo(
    /* id = */ UUID.randomUUID(),
    /* state = */ state,
    /* tags = */ emptySet(),
    /* outputData = */ outputData,
    /* progress = */ Data.EMPTY,
    /* runAttemptCount = */ 0,
    /* generation = */ 0,
)

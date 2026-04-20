package com.example.mob_dev_portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.ai.AnalysisRequest
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.ai.AnalysisService
import com.example.mob_dev_portfolio.data.ai.GeminiClient
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import com.example.mob_dev_portfolio.ui.analysis.AnalysisPhase
import com.example.mob_dev_portfolio.ui.analysis.AnalysisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Covers the ViewModel contract for the Analysis screen.
 *
 * The most important test in this file is [offline_tap_does_not_fire_request]
 * — that's the one pinned by the story's acceptance criterion: "tapping the
 * trigger button while offline immediately shows a non-blocking 'no network'
 * error". We verify the AnalysisService was NOT called at all, rather than
 * just that an error message appeared, so we know the short-circuit happens
 * before any network work is queued.
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
    fun offline_tap_does_not_fire_request() = runTest(testDispatcher) {
        val service = RecordingAnalysisService()
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = service,
            connectivity = { false },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()

        // Most important: the analysis service was not touched.
        assertEquals(0, service.callCount)
        assertEquals(AnalysisPhase.Idle, vm.state.value.phase)
        assertEquals("No internet — connect and try again.", vm.state.value.transientError)
    }

    @Test
    fun online_tap_goes_loading_then_success() = runTest(testDispatcher) {
        val service = RecordingAnalysisService(
            result = AnalysisResult.Success("You look stressed."),
            delayMs = 50,
        )
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = service,
            connectivity = { true },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        // Before scheduler advances, we should already be in Loading — the
        // state flip is synchronous on the caller dispatcher.
        testDispatcher.scheduler.runCurrent()
        assertTrue(
            "expected Loading immediately after tap, saw ${vm.state.value.phase}",
            vm.state.value.phase is AnalysisPhase.Loading,
        )

        testDispatcher.scheduler.advanceUntilIdle()
        val phase = vm.state.value.phase
        assertTrue("expected Success, got $phase", phase is AnalysisPhase.Success)
        assertEquals("You look stressed.", (phase as AnalysisPhase.Success).summaryText)
        assertEquals(1, service.callCount)
    }

    @Test
    fun api_error_surfaces_as_transient_error_not_crash() = runTest(testDispatcher) {
        val service = RecordingAnalysisService(result = AnalysisResult.ApiError("boom"))
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = service,
            connectivity = { true },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AnalysisPhase.Idle, vm.state.value.phase)
        assertEquals("boom", vm.state.value.transientError)
    }

    @Test
    fun no_network_result_from_client_is_mapped_to_friendly_error() = runTest(testDispatcher) {
        val service = RecordingAnalysisService(result = AnalysisResult.NoNetwork)
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = service,
            connectivity = { true }, // guard passed, but mid-request we dropped
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()

        val err = vm.state.value.transientError
        assertNotNull(err)
        // Distinct from the "offline before tap" copy — helps QA tell them apart.
        assertFalse(err!!.startsWith("No internet —"))
    }

    @Test
    fun double_tap_while_loading_does_not_fire_twice() = runTest(testDispatcher) {
        val service = RecordingAnalysisService(
            result = AnalysisResult.Success("done"),
            delayMs = 200,
        )
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = service,
            connectivity = { true },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.phase is AnalysisPhase.Loading)

        vm.triggerAnalysis() // second tap while still loading
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, service.callCount)
    }

    @Test
    fun logs_are_loaded_from_repository_before_analysis() = runTest(testDispatcher) {
        val service = RecordingAnalysisService(result = AnalysisResult.Success("ok"))
        val stored = listOf(
            SymptomLog(
                id = 1L,
                symptomName = "Migraine",
                description = "",
                startEpochMillis = 0L,
                endEpochMillis = null,
                severity = 5,
                medication = "",
                contextTags = emptyList(),
                notes = "",
            ),
        )
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(stored),
            analysisService = service,
            connectivity = { true },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, service.lastLogs.size)
        assertEquals("Migraine", service.lastLogs.first().symptomName)
    }

    @Test
    fun transient_error_shown_callback_clears_state() = runTest(testDispatcher) {
        val vm = AnalysisViewModel(
            profileRepository = FakeProfileRepository(),
            logRepository = FakeLogRepository(emptyList()),
            analysisService = RecordingAnalysisService(),
            connectivity = { false },
            ioDispatcher = testDispatcher,
        )

        vm.triggerAnalysis()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.transientError)

        vm.onTransientErrorShown()
        assertNull(vm.state.value.transientError)
    }

    // --- test doubles --------------------------------------------------

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

    private class FakeLogRepository(
        private val stored: List<SymptomLog>,
    ) : SymptomLogRepository(dao = UnusedDao) {
        override fun observeAll(): Flow<List<SymptomLog>> = flowOf(stored)
    }

    /**
     * The constructor requires a DataStore even though every real call goes
     * through the overridden [UserProfileRepository.profile] flow above.
     * This stand-in never has its data accessed; it exists only to satisfy
     * the type system.
     */
    private object UnusedDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableStateFlow(mutablePreferencesOf())
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = error("UnusedDataStore.updateData should not be called")
    }

    /** Same rationale as [UnusedDataStore] — satisfies the repository ctor. */
    private object UnusedDao : SymptomLogDao {
        override suspend fun insert(entity: SymptomLogEntity): Long = 0L
        override suspend fun update(entity: SymptomLogEntity): Int = 0
        override fun observeAll() = MutableStateFlow<List<SymptomLogEntity>>(emptyList())
        override fun observeFiltered(
            query: String?,
            minSeverity: Int,
            maxSeverity: Int,
            startAfter: Long?,
            startBefore: Long?,
            sortKey: String,
        ) = MutableStateFlow<List<SymptomLogEntity>>(emptyList())
        override fun observeById(id: Long) = MutableStateFlow<SymptomLogEntity?>(null)
        override fun observeCount() = MutableStateFlow(0)
        override suspend fun delete(id: Long) = Unit
    }

    private class RecordingAnalysisService(
        private val result: AnalysisResult = AnalysisResult.Success("ok"),
        private val delayMs: Long = 0,
    ) : AnalysisService(client = UnreachableClient) {
        var callCount: Int = 0
            private set
        var lastLogs: List<SymptomLog> = emptyList()
            private set

        override suspend fun analyze(
            profile: UserProfile,
            userContext: String,
            logs: List<SymptomLog>,
        ): AnalysisResult {
            callCount += 1
            lastLogs = logs
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    /**
     * The client never runs in ViewModel tests because RecordingAnalysisService
     * overrides [AnalysisService.analyze] in full. This stub exists solely to
     * satisfy the constructor without pulling in MockWebServer.
     */
    private object UnreachableClient : GeminiClient {
        override suspend fun analyze(request: AnalysisRequest): AnalysisResult =
            error("client should not be reached in ViewModel tests")
    }
}

package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogSymptomEditModeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun edit_mode_preloads_existing_log_into_draft() = runTest(dispatcher) {
        val existing = SymptomLog(
            id = 7L,
            symptomName = "Nausea",
            description = "After breakfast",
            startEpochMillis = 1_800_000_000_000L,
            endEpochMillis = 1_800_003_600_000L,
            severity = 6,
            medication = "",
            contextTags = listOf("stress"),
            notes = "Ginger tea helped",
            createdAtEpochMillis = 1_800_000_000_000L,
        )
        val repo = RecordingRepository(preload = existing)

        val vm = LogSymptomViewModel(repo, editingId = 7L, nowProvider = { 1_800_100_000_000L })
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Nausea", state.draft.symptomName)
        assertEquals("After breakfast", state.draft.description)
        assertEquals(6, state.draft.severity)
        assertTrue(state.draft.hasEnded)
        assertEquals(1_800_003_600_000L, state.draft.endEpochMillis)
        assertEquals(setOf("stress"), state.draft.contextTags)
        assertEquals(false, state.isLoading)
        assertEquals(7L, state.editingId)
    }

    @Test
    fun saving_in_edit_mode_calls_update_not_insert_and_keeps_createdAt() = runTest(dispatcher) {
        val existing = SymptomLog(
            id = 11L,
            symptomName = "Headache",
            description = "Dull ache",
            startEpochMillis = 1_800_000_000_000L,
            endEpochMillis = null,
            severity = 4,
            medication = "",
            contextTags = emptyList(),
            notes = "",
            createdAtEpochMillis = 1_700_000_000_000L,
        )
        val repo = RecordingRepository(preload = existing)

        val vm = LogSymptomViewModel(repo, editingId = 11L, nowProvider = { 1_900_000_000_000L })
        advanceUntilIdle()

        vm.onSeverityChange(9)
        var savedCallbackInvoked = false
        vm.save { savedCallbackInvoked = true }
        advanceUntilIdle()

        assertTrue("onSaved should be invoked", savedCallbackInvoked)
        assertEquals(0, repo.insertCount)
        assertEquals(1, repo.updateCount)
        val updated = repo.lastUpdate
        assertNotNull(updated)
        assertEquals(11L, updated!!.id)
        assertEquals(9, updated.severity)
        assertEquals(1_700_000_000_000L, updated.createdAtEpochMillis)
    }

    @Test
    fun saving_new_log_calls_insert_not_update() = runTest(dispatcher) {
        val repo = RecordingRepository(preload = null)
        val vm = LogSymptomViewModel(repo, editingId = 0L, nowProvider = { 1_900_000_000_000L })
        advanceUntilIdle()

        vm.onSymptomNameChange("Fever")
        vm.onDescriptionChange("38.5C reading")
        vm.save { }
        advanceUntilIdle()

        assertEquals(1, repo.insertCount)
        assertEquals(0, repo.updateCount)
    }

    @Test
    fun zero_row_update_surfaces_error_and_does_not_invoke_onSaved() = runTest(dispatcher) {
        val existing = SymptomLog(
            id = 13L,
            symptomName = "Stale",
            description = "Already deleted",
            startEpochMillis = 1_800_000_000_000L,
            endEpochMillis = null,
            severity = 3,
            medication = "",
            contextTags = emptyList(),
            notes = "",
            createdAtEpochMillis = 1_700_000_000_000L,
        )
        val repo = RecordingRepository(preload = existing, updateReturns = 0)
        val vm = LogSymptomViewModel(repo, editingId = 13L, nowProvider = { 1_900_000_000_000L })
        advanceUntilIdle()

        var savedCallbackInvoked = false
        vm.save { savedCallbackInvoked = true }
        advanceUntilIdle()

        assertFalse("onSaved must not fire when update affected 0 rows", savedCallbackInvoked)
        val state = vm.state.value
        assertNotNull("A transient error should be surfaced", state.transientError)
        assertEquals(false, state.isSaving)
    }
}

private class RecordingRepository(
    preload: SymptomLog?,
    private val updateReturns: Int = 1,
) : SymptomLogRepository(StubDao()) {

    var insertCount = 0
        private set
    var updateCount = 0
        private set
    var lastUpdate: SymptomLog? = null
        private set
    private val state = MutableStateFlow(preload)

    override fun observeById(id: Long): Flow<SymptomLog?> = state.asStateFlow().map { it }

    override suspend fun save(log: SymptomLog): Long {
        insertCount += 1
        return 1L
    }

    override suspend fun update(log: SymptomLog): Int {
        updateCount += 1
        lastUpdate = log
        return updateReturns
    }
}

private class StubDao : SymptomLogDao {
    override suspend fun insert(entity: SymptomLogEntity): Long = 0L
    override suspend fun update(entity: SymptomLogEntity): Int = 0
    override fun observeAll() = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ) = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeById(id: Long) = MutableStateFlow<SymptomLogEntity?>(null).asStateFlow()
    override fun observeCount() = MutableStateFlow(0).asStateFlow()
    override suspend fun delete(id: Long) = Unit
    override suspend fun listChronologicalAsc(): List<SymptomLogEntity> = emptyList()
    override suspend fun totalCount(): Int = 0
    override suspend fun averageSeverity(): Double? = null
}

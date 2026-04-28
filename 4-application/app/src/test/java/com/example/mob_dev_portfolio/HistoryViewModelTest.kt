package com.example.mob_dev_portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import com.example.mob_dev_portfolio.ui.history.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun log(
        id: Long,
        name: String,
        severity: Int,
        start: Long,
        tags: List<String> = emptyList(),
    ): SymptomLog = SymptomLog(
        id = id,
        symptomName = name,
        description = "desc-$id",
        startEpochMillis = start,
        endEpochMillis = null,
        severity = severity,
        medication = "",
        contextTags = tags,
        notes = "",
        createdAtEpochMillis = start,
    )

    @Test
    fun loads_stored_filter_into_state_on_init() = runTest(dispatcher) {
        val prefsStore = InMemoryPreferencesDataStore()
        val prefs = UiPreferencesRepository(prefsStore)
        prefs.setHistoryFilter(
            HistoryFilter(query = "headache", sort = HistorySort.NameAsc),
        )
        val repo = FakeRepository(emptyList())

        val vm = HistoryViewModel(repo, prefs, tagCatalog = listOf("Stress"))
        advanceUntilIdle()

        val state = vm.state.first { it.isLoaded }
        assertEquals("headache", state.filter.query)
        assertEquals(HistorySort.NameAsc, state.filter.sort)
        assertEquals(listOf("Stress"), state.availableTags)
    }

    @Test
    fun changing_filter_narrows_the_logs_flow() = runTest(dispatcher) {
        val prefs = UiPreferencesRepository(InMemoryPreferencesDataStore())
        val rows = listOf(
            log(1, "Headache", 4, 1_700_000_000_000L, tags = listOf("Stress")),
            log(2, "Nausea", 7, 1_800_000_000_000L, tags = listOf("Poor sleep")),
            log(3, "Migraine", 9, 1_900_000_000_000L, tags = listOf("Stress", "Weather change")),
        )
        val repo = FakeRepository(rows)
        val vm = HistoryViewModel(repo, prefs, tagCatalog = emptyList())
        advanceUntilIdle()

        vm.onQueryChange("head")
        advanceUntilIdle()
        val afterQuery = vm.logs.first { it.size == 1 }
        assertEquals(listOf(1L), afterQuery.map { it.id })

        vm.onQueryChange("")
        vm.onSeverityRangeChange(7, 10)
        advanceUntilIdle()
        val afterSeverity = vm.logs.first { it.all { log -> log.severity >= 7 } && it.size == 2 }
        assertEquals(setOf(2L, 3L), afterSeverity.map { it.id }.toSet())

        vm.onSeverityRangeChange(1, 10)
        vm.onToggleTag("Stress")
        advanceUntilIdle()
        val afterTag = vm.logs.first { it.all { log -> log.contextTags.contains("Stress") } }
        assertEquals(setOf(1L, 3L), afterTag.map { it.id }.toSet())
    }

    @Test
    fun clear_filters_resets_to_default() = runTest(dispatcher) {
        val prefs = UiPreferencesRepository(InMemoryPreferencesDataStore())
        val repo = FakeRepository(emptyList())
        val vm = HistoryViewModel(repo, prefs, tagCatalog = emptyList())
        advanceUntilIdle()
        vm.onQueryChange("x")
        vm.onSeverityRangeChange(5, 7)
        vm.onToggleTag("Stress")
        advanceUntilIdle()
        assertTrue(vm.filter.value.hasActiveFilters)

        vm.onClearFilters()
        advanceUntilIdle()
        assertFalse(vm.filter.value.hasActiveFilters)
        assertEquals(HistoryFilter.Default, vm.filter.value)
    }

    @Test
    fun filter_changes_persist_to_datastore_after_debounce() = runTest(dispatcher) {
        val store = InMemoryPreferencesDataStore()
        val prefs = UiPreferencesRepository(store)
        val vm = HistoryViewModel(FakeRepository(emptyList()), prefs, tagCatalog = emptyList())
        advanceUntilIdle()

        vm.onSortChange(HistorySort.SeverityDesc)
        advanceTimeBy(500L)
        advanceUntilIdle()

        val restored = prefs.historyFilter.first()
        assertEquals(HistorySort.SeverityDesc, restored.sort)
    }
}

private class FakeRepository(initial: List<SymptomLog>) : SymptomLogRepository(HistoryStubDao()) {
    private val source = MutableStateFlow(initial)

    override fun observeFiltered(filter: HistoryFilter): Flow<List<SymptomLog>> =
        source.map { rows ->
            val q = filter.query.trim().lowercase()
            rows.asSequence()
                .filter { log ->
                    (q.isEmpty() ||
                        log.symptomName.lowercase().contains(q) ||
                        log.description.lowercase().contains(q) ||
                        log.notes.lowercase().contains(q) ||
                        log.medication.lowercase().contains(q)) &&
                        log.severity in filter.minSeverity..filter.maxSeverity &&
                        (filter.startAfterEpochMillis == null || log.startEpochMillis >= filter.startAfterEpochMillis) &&
                        (filter.startBeforeEpochMillis == null || log.startEpochMillis <= filter.startBeforeEpochMillis) &&
                        (filter.tags.isEmpty() || filter.tags.all { log.contextTags.contains(it) })
                }
                .sortedWith(filter.sort.comparator())
                .toList()
        }
}

// Mirror of the DAO's `ORDER BY COALESCE(endEpochMillis, startEpochMillis)`
// clause. Date sorts key off the *end* timestamp so the History screen's
// "newest = most recently ended" semantic holds; ongoing logs (no end
// time) fall back to their start timestamp.
private fun HistorySort.comparator(): Comparator<SymptomLog> = when (this) {
    HistorySort.DateDesc -> compareByDescending { it.endEpochMillis ?: it.startEpochMillis }
    HistorySort.DateAsc -> compareBy { it.endEpochMillis ?: it.startEpochMillis }
    HistorySort.SeverityDesc -> compareByDescending { it.severity }
    HistorySort.SeverityAsc -> compareBy { it.severity }
    HistorySort.NameAsc -> compareBy { it.symptomName.lowercase() }
}

private class HistoryStubDao : SymptomLogDao {
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
    override suspend fun listChronologicalAsc(): List<SymptomLogEntity> = emptyList()
    override suspend fun totalCount(): Int = 0
    override suspend fun averageSeverity(): Double? = null
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
    override val data = flow

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        // Datastore's built-in extension snapshot-copies the prefs.
        val mutable = flow.value.toMutablePreferences()
        val updated = transform(mutable)
        flow.value = updated
        return updated
    }
}

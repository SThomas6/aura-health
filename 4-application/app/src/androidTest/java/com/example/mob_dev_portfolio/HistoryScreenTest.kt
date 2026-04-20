package com.example.mob_dev_portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistoryScreen
import com.example.mob_dev_portfolio.ui.history.HistoryViewModel
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun log(
        id: Long,
        name: String,
        severity: Int,
        tags: List<String> = emptyList(),
    ): SymptomLog = SymptomLog(
        id = id,
        symptomName = name,
        description = "$name description",
        startEpochMillis = 1_700_000_000_000L + id,
        endEpochMillis = null,
        severity = severity,
        medication = "",
        contextTags = tags,
        notes = "",
        createdAtEpochMillis = 1_700_000_000_000L + id,
    )

    private fun buildVm(
        logs: List<SymptomLog>,
        tagCatalog: List<String> = listOf("Stress", "Poor sleep"),
    ): HistoryViewModel = HistoryViewModel(
        repository = InMemoryRepository(logs),
        preferences = UiPreferencesRepository(InMemoryPreferencesDataStore()),
        tagCatalog = tagCatalog,
    )

    @Test
    fun empty_state_renders_when_no_logs() {
        val vm = buildVm(emptyList())
        composeRule.setContent { AuraTheme { HistoryScreen(onOpenLog = {}, viewModel = vm) } }

        composeRule.onNodeWithTag("history_empty").assertIsDisplayed()
    }

    @Test
    fun typing_in_search_filters_visible_rows() {
        val vm = buildVm(
            listOf(
                log(1, "Headache", 4),
                log(2, "Nausea", 6),
            ),
        )
        composeRule.setContent { AuraTheme { HistoryScreen(onOpenLog = {}, viewModel = vm) } }

        composeRule.onNodeWithTag("history_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("history_row_2").assertIsDisplayed()

        composeRule.onNodeWithTag("history_search").performTextInput("head")

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("history_row_1").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("history_row_2").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("history_row_1").assertIsDisplayed()
    }

    @Test
    fun opening_filter_sheet_reveals_sort_options() {
        val vm = buildVm(listOf(log(1, "Headache", 4)))
        composeRule.setContent { AuraTheme { HistoryScreen(onOpenLog = {}, viewModel = vm) } }

        composeRule.onNodeWithTag("btn_open_filters").performClick()

        composeRule.onNodeWithTag("filter_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("sort_NameAsc").assertIsDisplayed()
        composeRule.onNodeWithTag("sort_SeverityDesc").assertIsDisplayed()
    }

    @Test
    fun active_filter_chip_row_appears_when_query_present() {
        val vm = buildVm(listOf(log(1, "Headache", 4)))
        composeRule.setContent { AuraTheme { HistoryScreen(onOpenLog = {}, viewModel = vm) } }

        composeRule.onNodeWithTag("history_search").performTextInput("head")

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("history_active_filters").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Clear all").assertIsDisplayed()
    }
}

private class InMemoryRepository(initial: List<SymptomLog>) : SymptomLogRepository(InMemoryDao()) {
    private val source = MutableStateFlow(initial)

    override fun observeFiltered(filter: HistoryFilter): Flow<List<SymptomLog>> = source.map { rows ->
        val q = filter.query.trim().lowercase()
        rows.filter { log ->
            (q.isEmpty() || log.symptomName.lowercase().contains(q) || log.description.lowercase().contains(q)) &&
                log.severity in filter.minSeverity..filter.maxSeverity &&
                (filter.tags.isEmpty() || filter.tags.all { log.contextTags.contains(it) })
        }
    }

    override fun observeAll(): Flow<List<SymptomLog>> = source.asStateFlow()
}

private class InMemoryDao : SymptomLogDao {
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

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
    override val data = flow

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val mutable = flow.value.toMutablePreferences()
        val updated = transform(mutable)
        flow.value = updated
        return updated
    }

    private fun Preferences.toMutablePreferences(): MutablePreferences {
        val out = mutablePreferencesOf()
        asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            out[key as Preferences.Key<Any>] = value
        }
        return out
    }
}

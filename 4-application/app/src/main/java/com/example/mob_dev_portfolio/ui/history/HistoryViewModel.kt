package com.example.mob_dev_portfolio.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.condition.HealthCondition
import com.example.mob_dev_portfolio.data.condition.HealthConditionRepository
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.ui.log.LogValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state surfaced by [HistoryViewModel] for the History screen.
 *
 * [isLoaded] guards against rendering a flicker of the default filter
 * before the persisted filter has been read on cold start — the screen
 * uses it to suppress writing back to preferences until the first read
 * has completed (see [HistoryViewModel.init]).
 */
data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.Default,
    val isLoaded: Boolean = false,
    val availableTags: List<String> = emptyList(),
)

/**
 * Materialised "conditionId → name" map plus the inverse "logId →
 * condition" so the History composable can render condition headers and
 * decide which bucket a log belongs to in O(1).
 */
data class ConditionGrouping(
    val conditions: List<HealthCondition>,
    val byLogId: Map<Long, HealthCondition>,
) {
    companion object {
        val Empty = ConditionGrouping(emptyList(), emptyMap())
    }
}

/**
 * ViewModel for the History (Symptoms) screen.
 *
 * Exposes three StateFlows — [state], [logs] and [conditionGrouping] — so
 * the screen can collect each independently and only re-render the part
 * that changed. Filter persistence is handled here (rather than in the
 * preferences layer directly) so the screen stays trivially testable: a
 * fake [UiPreferencesRepository] is enough to assert the round-trip.
 *
 * `flatMapLatest` on the filter ensures that when a user types quickly,
 * the in-flight Room query for the previous filter is cancelled before
 * the new one starts — we don't surface stale results to the user. Filter
 * writes are debounced by [PERSIST_DEBOUNCE_MILLIS] so dragging the
 * severity slider doesn't hammer DataStore with intermediate values.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryViewModel(
    private val repository: SymptomLogRepository,
    private val preferences: UiPreferencesRepository,
    /**
     * Optional so existing test doubles that don't exercise the
     * conditions feature still compile — when null, the screen renders
     * without condition section headers.
     */
    private val conditionRepository: HealthConditionRepository? = null,
    tagCatalog: List<String>,
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter.Default)
    private val _isLoaded = MutableStateFlow(false)

    val state: StateFlow<HistoryUiState> = combine(_filter, _isLoaded) { filter, loaded ->
        HistoryUiState(filter = filter, isLoaded = loaded, availableTags = tagCatalog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(availableTags = tagCatalog),
    )

    val logs: StateFlow<List<SymptomLog>> = _filter
        // StateFlow already deduplicates via operator fusion, so an
        // explicit `distinctUntilChanged()` here would be a no-op.
        .flatMapLatest { filter -> repository.observeFiltered(filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Live map of declared conditions + the per-log mapping the screen
     * needs to render its grouped section headers. Resolved by joining
     * the conditions list with the (logId → conditionId) link table —
     * doing it here means the screen renders deterministically without
     * any Flow combine logic of its own.
     */
    val conditionGrouping: StateFlow<ConditionGrouping> =
        if (conditionRepository == null) {
            flowOf(ConditionGrouping.Empty)
        } else {
            combine(
                conditionRepository.observeAll(),
                conditionRepository.observeAllLinks(),
            ) { conditions, links ->
                if (conditions.isEmpty() || links.isEmpty()) {
                    ConditionGrouping(conditions = conditions, byLogId = emptyMap())
                } else {
                    val byId = conditions.associateBy { it.id }
                    val byLog = links.mapNotNull { link ->
                        byId[link.conditionId]?.let { link.logId to it }
                    }.toMap()
                    ConditionGrouping(conditions = conditions, byLogId = byLog)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ConditionGrouping.Empty)

    init {
        viewModelScope.launch {
            val stored = preferences.historyFilter.first()
            _filter.value = stored
            _isLoaded.value = true
            _filter
                .drop(1)
                .debounce(PERSIST_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collect { filter ->
                    runCatching { preferences.setHistoryFilter(filter) }
                }
        }
    }

    fun onQueryChange(query: String) {
        _filter.update { it.copy(query = query) }
    }

    fun onSeverityRangeChange(min: Int, max: Int) {
        val lo = min.coerceIn(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY)
        val hi = max.coerceIn(lo, LogValidator.MAX_SEVERITY)
        _filter.update { it.copy(minSeverity = lo, maxSeverity = hi) }
    }

    fun onDateRangeChange(startAfter: Long?, startBefore: Long?) {
        _filter.update {
            it.copy(
                startAfterEpochMillis = startAfter,
                startBeforeEpochMillis = startBefore,
            )
        }
    }

    fun onToggleTag(tag: String) {
        _filter.update {
            it.copy(tags = if (it.tags.contains(tag)) it.tags - tag else it.tags + tag)
        }
    }

    fun onSortChange(sort: HistorySort) {
        _filter.update { it.copy(sort = sort) }
    }

    fun onClearFilters() {
        _filter.value = HistoryFilter.Default
    }

    val filter: StateFlow<HistoryFilter> get() = _filter.asStateFlow()

    companion object {
        private const val PERSIST_DEBOUNCE_MILLIS = 200L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return HistoryViewModel(
                    repository = app.container.symptomLogRepository,
                    preferences = app.container.uiPreferencesRepository,
                    conditionRepository = app.container.healthConditionRepository,
                    tagCatalog = com.example.mob_dev_portfolio.data.ContextTagCatalog.tags,
                ) as T
            }
        }
    }
}

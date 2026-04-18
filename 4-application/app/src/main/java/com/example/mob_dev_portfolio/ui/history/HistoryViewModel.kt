package com.example.mob_dev_portfolio.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.Default,
    val isLoaded: Boolean = false,
    val availableTags: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryViewModel(
    private val repository: SymptomLogRepository,
    private val preferences: UiPreferencesRepository,
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
        .flatMapLatest { filter -> repository.observeFiltered(filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

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

    fun onStartAfterChange(epochMillis: Long?) {
        _filter.update { it.copy(startAfterEpochMillis = epochMillis) }
    }

    fun onStartBeforeChange(epochMillis: Long?) {
        _filter.update { it.copy(startBeforeEpochMillis = epochMillis) }
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
                    tagCatalog = com.example.mob_dev_portfolio.data.ContextTagCatalog.tags,
                ) as T
            }
        }
    }
}

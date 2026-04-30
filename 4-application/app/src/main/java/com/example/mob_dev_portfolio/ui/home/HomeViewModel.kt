package com.example.mob_dev_portfolio.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/**
 * ViewModel powering the Home screen's hero/insights area.
 *
 * Two parallel StateFlows are exposed because the screen renders the raw
 * recent-log list (for the "Recent activity" section) and the derived
 * [HomeInsights] (for the trend chart and stat tiles). Both stem from the
 * same `observeAll` flow but materialise different shapes — keeping them
 * separate lets the screen recompose only the part that actually changed.
 *
 * `WhileSubscribed(5_000L)` keeps the upstream Room flow alive across
 * config changes (rotation, theme switch) without leaking the subscription
 * forever — five seconds is long enough that a recreation re-attaches
 * before the cache is dropped.
 *
 * The `todayProvider` and `zone` parameters exist purely for testability:
 * production code uses the system defaults, but unit tests inject a
 * fixed clock so the trend window is deterministic.
 */
class HomeViewModel(
    repository: SymptomLogRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    /** All logs ordered by recency, observed live from Room. */
    val logs: StateFlow<List<SymptomLog>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Aggregated insights derived from [logs]. Recomputed on every list
     * change — cheap because the dataset is per-user and we never reach
     * the kind of size where this would be a hot path.
     */
    val insights: StateFlow<HomeInsights> = repository.observeAll()
        .map { HomeInsights.compute(it, today = todayProvider(), zone = zone) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            HomeInsights.compute(emptyList(), today = todayProvider(), zone = zone),
        )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return HomeViewModel(app.container.symptomLogRepository) as T
            }
        }
    }
}

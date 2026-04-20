package com.example.mob_dev_portfolio.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.ai.AnalysisHistoryRepository
import com.example.mob_dev_portfolio.data.ai.AnalysisRun
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Surface the persisted AI analysis history to the list screen.
 *
 * This is intentionally thin — the Room DAO already returns a Flow
 * sorted newest-first, so the ViewModel just re-exposes it as a
 * `StateFlow` for Compose. Two reasons for the `stateIn` conversion:
 *
 *  1. `collectAsStateWithLifecycle` wants a `StateFlow` for its snapshot
 *     semantics; otherwise the first recomposition shows `initialValue`
 *     until the first emission lands, which looks like a flash of
 *     "No history yet" before the real list paints.
 *  2. `SharingStarted.WhileSubscribed(5_000)` keeps the DAO flow hot
 *     across short config changes without leaving a cursor open when
 *     the screen is actually gone.
 */
class AnalysisHistoryViewModel(
    repository: AnalysisHistoryRepository,
) : ViewModel() {

    val runs: StateFlow<List<AnalysisRun>> = repository.runs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return AnalysisHistoryViewModel(
                    repository = app.container.analysisHistoryRepository,
                ) as T
            }
        }
    }
}

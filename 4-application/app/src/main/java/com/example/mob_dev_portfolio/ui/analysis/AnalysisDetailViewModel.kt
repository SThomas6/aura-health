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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Detail screen state.
 *
 * `Loading` is the initial emission from the Flow pipeline — the Room
 * query is asynchronous and the Compose screen renders a placeholder
 * until the first value arrives. `NotFound` covers the race where a row
 * has been deleted behind the UI's back (today we don't expose a delete
 * action, but notifications survive reboots so it's a real edge case).
 */
sealed interface AnalysisDetailState {
    data object Loading : AnalysisDetailState
    data object NotFound : AnalysisDetailState
    data class Loaded(
        val run: AnalysisRun,
        val linkedLogIds: List<Long>,
    ) : AnalysisDetailState
}

/**
 * ViewModel for the Analysis run detail view.
 *
 * We combine the run row and its linked log-ids into a single state so
 * the UI only has to subscribe to one Flow. Using a sentinel null for
 * "still loading" would conflate "loading" and "deleted"; the sealed
 * state discriminates them cleanly.
 */
class AnalysisDetailViewModel(
    private val repository: AnalysisHistoryRepository,
    private val runId: Long,
) : ViewModel() {

    val state: StateFlow<AnalysisDetailState> = combine(
        repository.observeRun(runId),
        repository.observeLogIdsFor(runId),
    ) { run, logIds ->
        when (run) {
            null -> AnalysisDetailState.NotFound
            else -> AnalysisDetailState.Loaded(run = run, linkedLogIds = logIds)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalysisDetailState.Loading,
    )

    /**
     * Fire-and-forget delete of the run this VM is bound to. The
     * cross-ref rows are removed by the ON DELETE CASCADE on the join
     * table, so the UI doesn't need to clean those up separately.
     *
     * [onDeleted] is invoked *first* (synchronously on the caller's
     * thread) so the screen pops before the Flow emits a null row and
     * transitions us to [AnalysisDetailState.NotFound] — otherwise the
     * user sees a brief "no longer available" flash between tapping
     * Delete and the back-navigation completing. The actual DB write
     * is then issued on viewModelScope so it survives the pop (the VM
     * lives until the history screen's re-subscription expires).
     */
    fun delete(onDeleted: () -> Unit) {
        onDeleted()
        viewModelScope.launch {
            repository.delete(runId)
        }
    }

    companion object {
        /**
         * Factory that closes over the [runId] route argument so the VM
         * can be instantiated by `viewModel(factory = ...)`. The
         * AuraApplication is reached via [CreationExtras] (rather than
         * a constructor injection framework) to keep the dependency
         * container in plain Kotlin without pulling in Hilt/Dagger.
         */
        fun factory(runId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return AnalysisDetailViewModel(
                    repository = app.container.analysisHistoryRepository,
                    runId = runId,
                ) as T
            }
        }
    }
}

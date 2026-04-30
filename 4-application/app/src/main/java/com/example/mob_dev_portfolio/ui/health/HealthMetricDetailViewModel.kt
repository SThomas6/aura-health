package com.example.mob_dev_portfolio.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the fullscreen metric detail screen. [series] is null while
 * the first read is in flight so the UI can render an inline spinner
 * rather than blinking an empty chart.
 */
data class HealthMetricDetailState(
    val metric: HealthConnectMetric,
    val range: HealthHistoryRepository.Range = HealthHistoryRepository.Range.Week,
    val series: HealthHistoryRepository.Series? = null,
    val loading: Boolean = true,
)

/**
 * Powers [HealthMetricDetailScreen]. Re-reads the selected metric's
 * series from Health Connect every time the range toggle changes.
 */
class HealthMetricDetailViewModel(
    private val metric: HealthConnectMetric,
    private val historyRepository: HealthHistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthMetricDetailState(metric = metric))
    val state: StateFlow<HealthMetricDetailState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload(_state.value.range)
    }

    /**
     * Switches the time-range window. No-op when the range is unchanged
     * to avoid kicking off a redundant Health Connect read on a recompose
     * that happens to fire the segmented button's `onClick` for the
     * already-selected option.
     */
    fun setRange(range: HealthHistoryRepository.Range) {
        if (_state.value.range == range) return
        _state.value = _state.value.copy(range = range, loading = true)
        reload(range)
    }

    private fun reload(range: HealthHistoryRepository.Range) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val series = historyRepository.readSeries(metric, range)
            _state.value = HealthMetricDetailState(
                metric = metric,
                range = range,
                series = series,
                loading = false,
            )
        }
    }

    companion object {
        /**
         * Factory for a specific metric — the screen composable hands
         * over the storageKey resolved from the route args. Unknown keys
         * fall back to Steps so the screen doesn't crash on a bad deep link.
         */
        fun factory(metricStorageKey: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                    val metric = HealthConnectMetric.fromStorageKey(metricStorageKey)
                        ?: HealthConnectMetric.Steps
                    return HealthMetricDetailViewModel(
                        metric = metric,
                        historyRepository = app.container.healthHistoryRepository,
                    ) as T
                }
            }
    }
}

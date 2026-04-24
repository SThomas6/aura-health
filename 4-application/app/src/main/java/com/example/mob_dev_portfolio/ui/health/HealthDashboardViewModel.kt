package com.example.mob_dev_portfolio.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthConnectService
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.data.health.HealthPreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * State for a single Home-dashboard metric card.
 */
data class MetricCardState(
    val metric: HealthConnectMetric,
    val series: HealthHistoryRepository.Series,
    /** Cached headline value for the card so the UI doesn't re-derive it. */
    val headlineValue: Double?,
)

/**
 * State for the health dashboard section of Home.
 *
 *  - [loading] — true while we're doing the first batch of reads;
 *  - [sdkAvailable] — false if Health Connect isn't installed (the section
 *    hides in that case rather than showing an error);
 *  - [connectionActive] — whether the user has connected HC through the
 *    app. The dashboard shows whenever this is true; it's deliberately
 *    independent of the "Include in AI analysis" preference so the user
 *    can keep the charts on Home while opting their readings out of the
 *    AI prompt;
 *  - [cards] — one card per enabled + granted metric, in catalogue order.
 */
data class HealthDashboardState(
    val loading: Boolean = true,
    val sdkAvailable: Boolean = true,
    val connectionActive: Boolean = false,
    val cards: List<MetricCardState> = emptyList(),
)

/**
 * Drives the Home-screen health data section. Observes the user's
 * enabled-metric preferences, filters them by currently-granted permissions,
 * and reloads the 7-day series for each readable metric whenever the set
 * changes. Also exposes a [refresh] for the screen's pull-to-refresh
 * affordance and a grant-changed re-read.
 */
class HealthDashboardViewModel(
    private val preferencesRepository: HealthPreferencesRepository,
    private val historyRepository: HealthHistoryRepository,
    private val healthConnectService: HealthConnectService,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthDashboardState())
    val state: StateFlow<HealthDashboardState> = _state.asStateFlow()

    /**
     * The in-flight reload. Cancelled when a new one starts so the UI
     * never shows a stale result after the user toggles a metric off.
     */
    private var reloadJob: Job? = null

    init {
        // A change to either the connection state or the per-metric set
        // re-triggers a reload. The dashboard is deliberately NOT bound
        // to integrationEnabled (the AI toggle) — the user can opt out
        // of AI inclusion and still want the dashboard visible.
        combine(
            preferencesRepository.connectionActive,
            preferencesRepository.enabledMetrics,
        ) { connectionOn, metrics ->
            connectionOn to metrics
        }.onEach { (connectionOn, metrics) ->
            reload(connectionOn, metrics)
        }.launchIn(viewModelScope)
    }

    /** Re-check grants and reload. Call on resume. */
    fun refresh() {
        val current = _state.value
        reload(current.connectionActive, current.cards.map { it.metric }.toSet(), force = true)
    }

    private fun reload(connectionOn: Boolean, enabled: Set<HealthConnectMetric>, force: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val status = healthConnectService.status()
            val available = status == HealthConnectService.Status.Available
            if (!available) {
                _state.value = HealthDashboardState(
                    loading = false,
                    sdkAvailable = false,
                    connectionActive = connectionOn,
                    cards = emptyList(),
                )
                return@launch
            }
            if (!connectionOn || enabled.isEmpty()) {
                _state.value = HealthDashboardState(
                    loading = false,
                    sdkAvailable = true,
                    connectionActive = connectionOn,
                    cards = emptyList(),
                )
                return@launch
            }
            _state.value = _state.value.copy(loading = true, sdkAvailable = true, connectionActive = connectionOn)
            val readable = healthConnectService.readableMetrics(enabled)
            // Preserve catalogue declaration order so the dashboard ordering
            // is stable across reloads.
            val ordered = HealthConnectMetric.entries.filter { it in readable }
            val cards = ordered.mapNotNull { metric ->
                val series = historyRepository.readSeries(metric, HealthHistoryRepository.Range.Week)
                val headline = HealthMetricFormat.headline(metric, series.summary)
                // Skip metrics with no meaningful readings — an empty card
                // just clutters the dashboard without telling the user
                // anything useful. The master toggle screen still exposes
                // the metric so the user can see what's available.
                val hasAnyData = series.points.any { it.value != 0.0 } ||
                    (headline != null && headline != 0.0)
                if (!hasAnyData) return@mapNotNull null
                MetricCardState(
                    metric = metric,
                    series = series,
                    headlineValue = headline,
                )
            }
            _state.value = HealthDashboardState(
                loading = false,
                sdkAvailable = true,
                connectionActive = connectionOn,
                cards = cards,
            )
            // `force` just keeps the signature symmetric — a future UI could
            // branch on it if we add a "no fresh read if < 30s old" cache.
            @Suppress("UNUSED_EXPRESSION") force
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return HealthDashboardViewModel(
                    preferencesRepository = app.container.healthPreferencesRepository,
                    historyRepository = app.container.healthHistoryRepository,
                    healthConnectService = app.container.healthConnectService,
                ) as T
            }
        }
    }
}

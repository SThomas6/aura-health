package com.example.mob_dev_portfolio.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthConnectService
import com.example.mob_dev_portfolio.data.health.HealthMetricCategory
import com.example.mob_dev_portfolio.data.health.HealthPreferencesRepository
import com.example.mob_dev_portfolio.data.health.HealthSampleSeeder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the Health Data Settings screen.
 *
 * A single data class rather than separate flows so the screen binds once
 * and recomposes atomically — the SDK status, the master toggle, and the
 * per-metric rows all change together when the user disconnects, which
 * would otherwise be a visible flicker if they were independent streams.
 */
data class HealthSettingsUiState(
    val sdkStatus: HealthConnectService.Status = HealthConnectService.Status.Available,
    /** Has the user connected HC via this app? Drives the Connect/Disconnect CTA. */
    val connectionActive: Boolean = false,
    /** Is the user opting in to having HC data in AI prompts? Independent toggle. */
    val integrationEnabled: Boolean = false,
    val metricRows: List<HealthMetricRowState> = emptyList(),
    val transientMessage: String? = null,
)

/**
 * One row in the metric list. The [granted] and [enabled] flags reflect
 * independent axes:
 *
 *  - [enabled] — the user wants this metric to be considered (DataStore
 *    toggle);
 *  - [granted] — the user has given Health Connect read permission
 *    (system grant state).
 *
 * The UI draws a slightly different chrome for each cell in the 2x2
 * matrix — e.g. "Enabled but not granted → 'Grant permission' CTA".
 */
data class HealthMetricRowState(
    val metric: HealthConnectMetric,
    val enabled: Boolean,
    val granted: Boolean,
)

class HealthDataSettingsViewModel(
    private val healthConnectService: HealthConnectService,
    private val preferencesRepository: HealthPreferencesRepository,
    private val sampleSeeder: HealthSampleSeeder,
) : ViewModel() {

    /**
     * Grant state is held locally because the SDK's grant query is a
     * suspend call, not a Flow. We refresh it on [refreshGrants] — the
     * screen calls that from a lifecycle effect and after returning from
     * the permission UI so toggles stay in sync with reality.
     */
    private val grantedPermissions: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private val transientMessageFlow: MutableStateFlow<String?> = MutableStateFlow(null)

    val state: StateFlow<HealthSettingsUiState> = combine(
        preferencesRepository.connectionActive,
        preferencesRepository.integrationEnabled,
        preferencesRepository.enabledMetrics,
        grantedPermissions,
        transientMessageFlow,
    ) { connectionOn, integrationOn, enabled, granted, message ->
        HealthSettingsUiState(
            sdkStatus = healthConnectService.status(),
            connectionActive = connectionOn,
            integrationEnabled = integrationOn,
            metricRows = HealthConnectMetric.entries.map { metric ->
                HealthMetricRowState(
                    metric = metric,
                    enabled = metric in enabled,
                    granted = metric.readPermission in granted,
                )
            },
            transientMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HealthSettingsUiState(),
    )

    init {
        refreshGrants()
    }

    /** Re-read Health Connect grant state. Safe to call repeatedly. */
    fun refreshGrants() {
        viewModelScope.launch {
            grantedPermissions.value = runCatching {
                healthConnectService.grantedPermissions()
            }.getOrDefault(emptySet())
        }
    }

    fun setIntegrationEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setIntegrationEnabled(enabled) }
    }

    fun setMetricEnabled(metric: HealthConnectMetric, enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setEnabled(metric, enabled) }
    }

    /**
     * "Disconnect" action — clears every in-app opt-in **and** revokes
     * the HC permission set. Revoking is important: without it, tapping
     * Connect again would see the existing grants and the system
     * permission contract would return immediately without a dialog,
     * making the reconnect flow silent and confusing. Revoke + clear
     * guarantees the next Connect is a fresh prompt.
     */
    fun disconnect() {
        viewModelScope.launch {
            runCatching { healthConnectService.revokeAllPermissions() }
            preferencesRepository.clearAll()
            // Reflect the now-empty grant set locally so the UI re-paints
            // every row as ungranted without waiting for the next screen
            // resume to re-read.
            grantedPermissions.value = emptySet()
            transientMessageFlow.value = "Disconnected. Tap Connect to re-enable."
        }
    }

    /**
     * Called from the screen after the permission-request contract
     * returns. We persist the "I granted something → flip the master
     * switch on" heuristic here so the user isn't left with a disabled
     * integration and active grants. Also auto-seeds 14 days of demo
     * data on first connect so the dashboard has graphs to render.
     *
     * Notice we flip BOTH [connectionActive] and [integrationEnabled] on
     * — Connect defaults the AI toggle on so the common case is "tap
     * once, everything works". Users who want to stay connected but opt
     * out of AI inclusion flip that toggle off separately.
     */
    fun onPermissionsResult(grantedNow: Set<String>) {
        grantedPermissions.value = grantedNow
        if (grantedNow.isNotEmpty()) {
            viewModelScope.launch {
                preferencesRepository.setConnectionActive(true)
                preferencesRepository.setIntegrationEnabled(true)
                maybeAutoSeed()
            }
        }
    }

    /**
     * Auto-seed 14 days of plausible sample data on every successful
     * Connect. We deliberately do NOT gate on [sampleSeeded] — the
     * seeder is idempotent via deterministic `clientRecordId`s and
     * purges its own prior records before writing, so a re-Connect
     * always lands the user on a fresh two-week window ending today.
     *
     * Why drop the "only seed once" guard?
     *   - On the S10e and Samsung HC app, the user's phone keeps
     *     writing its own real data (12 steps, today's real weight,
     *     185 cm height) between Connect sessions. If we only seeded
     *     once, a user who disconnects and reconnects would see their
     *     real "12 steps today" regress the seeded values because
     *     today's real record is newer than the week-old seed.
     *   - The seeder's HC-side upsert semantics mean re-running is
     *     free — it's a handful of insertRecords calls that HC resolves
     *     to no-op or replacement writes.
     *   - The [sampleSeeded] preference is still flipped on success,
     *     kept purely as a telemetry signal for future analytics.
     *
     * Silent on both success and failure — the dashboard is the
     * user-visible affordance. Logcat shows the counts (see
     * [HealthSampleSeeder.TAG]) when debugging.
     */
    private suspend fun maybeAutoSeed() {
        val result = runCatching { sampleSeeder.seedTwoWeeks() }.getOrNull() ?: return
        if (result.ok) {
            preferencesRepository.setSampleSeeded(true)
        }
    }

    fun dismissMessage() {
        transientMessageFlow.value = null
    }

    /**
     * Permission set to request when the user taps "grant". Covers read
     * for every catalogue metric plus the write permissions the
     * auto-seeder needs — requesting them together means the user sees
     * a single system dialog rather than two back-to-back, and the
     * seeder can run silently once grants land.
     */
    fun allCatalogPermissions(): Set<String> =
        HealthConnectMetric.entries.map { it.readPermission }.toSet() +
            sampleSeeder.writePermissions()

    /**
     * Helper to group the metric rows by category for the settings UI.
     * Done here (rather than in the composable) so a future screenshot
     * test can pin the ordering.
     */
    fun groupedRows(): Map<HealthMetricCategory, List<HealthMetricRowState>> =
        state.value.metricRows.groupBy { it.metric.category }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return HealthDataSettingsViewModel(
                    healthConnectService = app.container.healthConnectService,
                    preferencesRepository = app.container.healthPreferencesRepository,
                    sampleSeeder = app.container.healthSampleSeeder,
                ) as T
            }
        }
    }
}

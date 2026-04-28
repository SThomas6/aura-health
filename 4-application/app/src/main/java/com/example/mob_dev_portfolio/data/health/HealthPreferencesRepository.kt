package com.example.mob_dev_portfolio.data.health

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted Health Connect opt-in state.
 *
 * The user's toggles are stored here and survive kill/reinstall. They are
 * intentionally **not** kept in the encrypted Room database — toggles are
 * not PII, and keeping them in DataStore means the Settings screen can
 * render instantly on a fresh launch without unlocking SQLCipher.
 *
 * Three **orthogonal** pieces of state live here:
 *
 *   - [connectionActive] — "I've connected Health Connect and granted the
 *     permissions". Drives whether the Home dashboard section is shown
 *     and whether the sample-seeder has been offered. Flipping this off
 *     means "disconnect" (revoke perms, hide the dashboard).
 *
 *   - [integrationEnabled] — "Include health data in AI analysis". A
 *     user can stay connected (dashboard visible) but opt out of having
 *     their readings fed into the analysis prompt. Gated on
 *     [connectionActive] — there's nothing to include if the user isn't
 *     connected.
 *
 *   - [enabledMetrics] — the per-metric subset the AI analysis considers.
 *
 * The old builds only had the "integrationEnabled" concept, which
 * conflated Connect with AI-inclusion. Existing users who had
 * `integrationEnabled = true` are migrated to `connectionActive = true`
 * on first read by the [connectionActive] flow's default fall-through.
 */
open class HealthPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * True when the user has set up Health Connect through the app. When
     * this flips from `true` to `false` we revoke HC permissions and wipe
     * the seed flag so a re-Connect starts fresh.
     *
     * Migration: if the key hasn't been written yet, fall back to the old
     * [INTEGRATION_ENABLED] key so existing users don't appear to be
     * suddenly disconnected.
     */
    open val connectionActive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CONNECTION_ACTIVE] ?: prefs[INTEGRATION_ENABLED] ?: false
    }

    /**
     * True when HC readings should be included in AI analysis prompts.
     * Independent of [connectionActive] — toggle this off to keep the
     * dashboard but redact readings from the AI.
     */
    open val integrationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[INTEGRATION_ENABLED] ?: false
    }

    /**
     * Latched once the one-shot sample seeder has run successfully.
     * Read on Connect to decide whether to quietly auto-seed two weeks of
     * demo data in the background.
     */
    open val sampleSeeded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SAMPLE_SEEDED] ?: false
    }

    open suspend fun setSampleSeeded(seeded: Boolean) {
        dataStore.edit { it[SAMPLE_SEEDED] = seeded }
    }

    open val enabledMetrics: Flow<Set<HealthConnectMetric>> = dataStore.data.map { prefs ->
        // First-ever read returns null → use the catalogue's default set.
        // We don't persist on read, so toggling one off on first run
        // writes the full delta and locks in the user's intent without
        // a double emission.
        prefs[ENABLED_METRICS]
            ?.mapNotNull { HealthConnectMetric.fromStorageKey(it) }
            ?.toSet()
            ?: HealthConnectMetric.DefaultEnabled
    }

    open suspend fun setIntegrationEnabled(enabled: Boolean) {
        dataStore.edit { it[INTEGRATION_ENABLED] = enabled }
    }

    open suspend fun setConnectionActive(active: Boolean) {
        dataStore.edit { it[CONNECTION_ACTIVE] = active }
    }

    open suspend fun setEnabled(metric: HealthConnectMetric, enabled: Boolean) {
        dataStore.edit { editor ->
            val current: Set<String> = editor[ENABLED_METRICS]
                ?: HealthConnectMetric.DefaultEnabled.map { it.storageKey }.toSet()
            editor[ENABLED_METRICS] = if (enabled) current + metric.storageKey
            else current - metric.storageKey
        }
    }

    /**
     * Used by the "disconnect" action to clear every in-app opt-in at
     * once. Also clears [SAMPLE_SEEDED] so a future re-connect re-runs
     * the auto-seeder for a fresh demo experience; HC grants themselves
     * are revoked separately by the caller via
     * [HealthConnectService.revokeAllPermissions].
     */
    open suspend fun clearAll() {
        dataStore.edit { editor ->
            editor[CONNECTION_ACTIVE] = false
            editor[INTEGRATION_ENABLED] = false
            editor[ENABLED_METRICS] = emptySet()
            editor[SAMPLE_SEEDED] = false
        }
    }

    companion object {
        private val CONNECTION_ACTIVE = booleanPreferencesKey("hc_connection_active")
        private val INTEGRATION_ENABLED = booleanPreferencesKey("hc_integration_enabled")
        private val ENABLED_METRICS = stringSetPreferencesKey("hc_enabled_metrics")
        private val SAMPLE_SEEDED = booleanPreferencesKey("hc_sample_seeded")
    }
}

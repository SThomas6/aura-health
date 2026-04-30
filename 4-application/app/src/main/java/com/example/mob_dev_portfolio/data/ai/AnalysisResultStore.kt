package com.example.mob_dev_portfolio.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The most recent **successful** AI analysis, persisted so the result
 * survives:
 *   - the app process being killed under memory pressure,
 *   - the user swiping the app from Recents mid-analysis (the worker keeps
 *     running; the next time MainActivity is launched — possibly from the
 *     result notification — the Analysis screen reads this store and
 *     shows the summary),
 *   - device reboots while an analysis sits unread.
 *
 * Failures are **not** stored here. The notification the worker posts is
 * the authoritative UX for a failed run; persisting "the last thing failed"
 * would force the UI to choose between showing stale success and stale
 * failure. We prefer the simplest rule: "the store holds the last good
 * summary, full stop".
 */
data class StoredAnalysis(
    val summaryText: String,
    val guidance: AnalysisGuidance,
    val completedAtEpochMillis: Long,
)

/**
 * DataStore-backed persistence for the latest [StoredAnalysis] (see
 * the doc comment on the data class above for what we persist and why
 * only successes land here).
 *
 * Stored in its own preferences file (`aura_analysis_result`) rather
 * than the encrypted Room database so the result is reachable from
 * the notification-tap deep-link path without unlocking SQLCipher —
 * a tap on the lock-screen notification immediately renders the
 * summary even if the user hasn't authenticated yet.
 *
 * Methods are `open` so view-model unit tests can swap in an in-memory
 * fake without touching the real DataStore plumbing.
 */
open class AnalysisResultStore(
    private val dataStore: DataStore<Preferences>,
) {

    open val latest: Flow<StoredAnalysis?> = dataStore.data.map { prefs ->
        val summary = prefs[SUMMARY] ?: return@map null
        val guidanceName = prefs[GUIDANCE] ?: return@map null
        val guidance = runCatching { AnalysisGuidance.valueOf(guidanceName) }.getOrNull()
            ?: return@map null
        val completed = prefs[COMPLETED_AT] ?: return@map null
        StoredAnalysis(
            summaryText = summary,
            guidance = guidance,
            completedAtEpochMillis = completed,
        )
    }

    open suspend fun save(
        summaryText: String,
        guidance: AnalysisGuidance,
        completedAtEpochMillis: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[SUMMARY] = summaryText
            prefs[GUIDANCE] = guidance.name
            prefs[COMPLETED_AT] = completedAtEpochMillis
        }
    }

    open suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val SUMMARY = stringPreferencesKey("analysis_summary")
        val GUIDANCE = stringPreferencesKey("analysis_guidance")
        val COMPLETED_AT = longPreferencesKey("analysis_completed_at")
    }
}

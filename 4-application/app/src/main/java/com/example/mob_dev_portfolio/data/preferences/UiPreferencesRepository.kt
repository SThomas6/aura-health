package com.example.mob_dev_portfolio.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import com.example.mob_dev_portfolio.ui.log.LogValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Three-way colour scheme switch. `System` follows the device-level dark mode. */
enum class ThemeMode { System, Light, Dark }

/** Snapshot of the UI-level preference keys aggregated for the theme controller. */
data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
)

/**
 * DataStore-backed repository for UI-only preferences (theme, onboarding
 * gate, biometric lock, history filter, last-used trend overlay,
 * medication reminders kill-switch).
 *
 * Lives in the unencrypted Preferences DataStore (not SQLCipher) because:
 *   • None of the keys are PII or health data — they're UI state.
 *   • The biometric-lock + onboarding gate must be readable *before* the
 *     SQLCipher DB is unlocked, so they can't depend on it.
 *
 * The class is `open` so the test layer can subclass and stub flows
 * without spinning up a real DataStore — the rest of the codebase uses
 * the same pattern (see [UserProfileRepository], [HealthPreferencesRepository]).
 */
open class UiPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {

    open val preferences: Flow<UiPreferences> = dataStore.data.map { prefs ->
        UiPreferences(
            themeMode = prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System,
        )
    }

    open suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { editor ->
            editor[THEME_MODE] = mode.name
        }
    }

    /**
     * First-launch onboarding state.
     *
     * The flag flips to `true` once the user steps through the welcome +
     * permission-rationale screens, after which [com.example.mob_dev_portfolio.ui.AuraApp] takes over as
     * the root UI. A dedicated boolean (rather than reusing, say, an
     * installed-version int) means clearing the flag remotely in a
     * future migration is as simple as deleting the key.
     */
    open val onboardingComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETE] ?: false
    }

    open suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { editor ->
            editor[ONBOARDING_COMPLETE] = complete
        }
    }

    /**
     * When true, the app is gated behind a BiometricPrompt on every
     * cold start and whenever it's resumed after being backgrounded.
     * Stored as a plain boolean (rather than tied to a device-bound
     * key) because BiometricPrompt itself proves presence — we don't
     * use the biometric to unwrap secrets, just to authorise UI access.
     *
     * Defaults to **true** because the app holds sensitive health data
     * (symptom logs, AI analyses, Health Connect readings) and a
     * lost/shared device is a real threat model. MainActivity's gate
     * also runs a runtime `canAuthenticate` check so devices with no
     * enrolled biometric and no device credential fall straight
     * through to the app — the default-on pref never leaves a user
     * stranded on a lock screen they can't clear.
     */
    open val biometricLockEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[BIOMETRIC_LOCK_ENABLED] ?: true
    }

    open suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { editor ->
            editor[BIOMETRIC_LOCK_ENABLED] = enabled
        }
    }

    open val historyFilter: Flow<HistoryFilter> = dataStore.data.map { prefs ->
        val min = prefs[HISTORY_MIN_SEVERITY] ?: LogValidator.MIN_SEVERITY
        val max = prefs[HISTORY_MAX_SEVERITY] ?: LogValidator.MAX_SEVERITY
        HistoryFilter(
            query = prefs[HISTORY_QUERY].orEmpty(),
            minSeverity = min.coerceIn(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY),
            maxSeverity = max.coerceIn(min, LogValidator.MAX_SEVERITY),
            startAfterEpochMillis = prefs[HISTORY_START_AFTER],
            startBeforeEpochMillis = prefs[HISTORY_START_BEFORE],
            tags = prefs[HISTORY_TAGS]?.toSet() ?: emptySet(),
            sort = HistorySort.fromName(prefs[HISTORY_SORT]),
        )
    }

    open suspend fun setHistoryFilter(filter: HistoryFilter) {
        dataStore.edit { editor ->
            if (filter.query.isBlank()) editor.remove(HISTORY_QUERY)
            else editor[HISTORY_QUERY] = filter.query
            editor[HISTORY_MIN_SEVERITY] = filter.minSeverity
            editor[HISTORY_MAX_SEVERITY] = filter.maxSeverity
            if (filter.startAfterEpochMillis == null) editor.remove(HISTORY_START_AFTER)
            else editor[HISTORY_START_AFTER] = filter.startAfterEpochMillis
            if (filter.startBeforeEpochMillis == null) editor.remove(HISTORY_START_BEFORE)
            else editor[HISTORY_START_BEFORE] = filter.startBeforeEpochMillis
            if (filter.tags.isEmpty()) editor.remove(HISTORY_TAGS)
            else editor[HISTORY_TAGS] = filter.tags
            editor[HISTORY_SORT] = filter.sort.name
        }
    }

    /**
     * Id of the overlay the user most recently toggled *on* in the
     * fullscreen Trends dashboard. The Home preview card reads this so
     * the mini-chart reflects the user's most recent pick rather than
     * always showing a bare symptom line. Null until the first toggle
     * — the ViewModel decides what default to render in that case
     * (currently the Sleep HC metric, as the "most common" overlay).
     */
    open val lastTrendOverlayId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[LAST_TREND_OVERLAY_ID]
    }

    open suspend fun setLastTrendOverlayId(id: String?) {
        dataStore.edit { editor ->
            if (id == null) editor.remove(LAST_TREND_OVERLAY_ID)
            else editor[LAST_TREND_OVERLAY_ID] = id
        }
    }

    /**
     * Global kill-switch for medication reminders (FR-MR-08).
     *
     * Defaults to **true** so a user who's just created their first
     * reminder doesn't have to hunt through Settings to make it
     * actually fire. Flipping it off preserves every configured
     * schedule — the receiver checks this flag and skips posting, and
     * the scheduler cancels the platform alarms on the flip so no
     * stale fire-times linger in the background.
     */
    open val medicationRemindersEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[MEDICATION_REMINDERS_ENABLED] ?: true
    }

    open suspend fun setMedicationRemindersEnabled(enabled: Boolean) {
        dataStore.edit { editor ->
            editor[MEDICATION_REMINDERS_ENABLED] = enabled
        }
    }

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        private val HISTORY_QUERY = stringPreferencesKey("history_query")
        private val HISTORY_MIN_SEVERITY = intPreferencesKey("history_min_severity")
        private val HISTORY_MAX_SEVERITY = intPreferencesKey("history_max_severity")
        private val HISTORY_START_AFTER = longPreferencesKey("history_start_after")
        private val HISTORY_START_BEFORE = longPreferencesKey("history_start_before")
        private val HISTORY_TAGS = stringSetPreferencesKey("history_tags")
        private val HISTORY_SORT = stringPreferencesKey("history_sort")
        private val LAST_TREND_OVERLAY_ID = stringPreferencesKey("last_trend_overlay_id")
        private val MEDICATION_REMINDERS_ENABLED = booleanPreferencesKey("medication_reminders_enabled")
    }
}

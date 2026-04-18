package com.example.mob_dev_portfolio.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

enum class ThemeMode { System, Light, Dark }

data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
)

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

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val HISTORY_QUERY = stringPreferencesKey("history_query")
        private val HISTORY_MIN_SEVERITY = intPreferencesKey("history_min_severity")
        private val HISTORY_MAX_SEVERITY = intPreferencesKey("history_max_severity")
        private val HISTORY_START_AFTER = longPreferencesKey("history_start_after")
        private val HISTORY_START_BEFORE = longPreferencesKey("history_start_before")
        private val HISTORY_TAGS = stringSetPreferencesKey("history_tags")
        private val HISTORY_SORT = stringPreferencesKey("history_sort")
    }
}

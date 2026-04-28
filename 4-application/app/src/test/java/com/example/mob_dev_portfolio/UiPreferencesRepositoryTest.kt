package com.example.mob_dev_portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiPreferencesRepositoryTest {

    @Test
    fun default_theme_mode_is_system() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)

        val prefs = repo.preferences.first()

        assertEquals(ThemeMode.System, prefs.themeMode)
    }

    @Test
    fun setting_theme_mode_dark_persists_and_emits() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)

        repo.setThemeMode(ThemeMode.Dark)

        val prefs = repo.preferences.first()
        assertEquals(ThemeMode.Dark, prefs.themeMode)
    }

    @Test
    fun setting_theme_mode_light_then_system_persists_latest() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)

        repo.setThemeMode(ThemeMode.Light)
        repo.setThemeMode(ThemeMode.System)

        val prefs = repo.preferences.first()
        assertEquals(ThemeMode.System, prefs.themeMode)
    }

    @Test
    fun history_filter_defaults_when_nothing_stored() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)

        val filter = repo.historyFilter.first()

        assertEquals(HistoryFilter.Default, filter)
    }

    @Test
    fun setting_history_filter_roundtrips_all_fields() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)
        val original = HistoryFilter(
            query = "headache",
            minSeverity = 3,
            maxSeverity = 8,
            startAfterEpochMillis = 1_700_000_000_000L,
            startBeforeEpochMillis = 1_800_000_000_000L,
            tags = setOf("Stress", "Poor sleep"),
            sort = HistorySort.SeverityDesc,
        )

        repo.setHistoryFilter(original)

        val restored = repo.historyFilter.first()
        assertEquals(original, restored)
    }

    @Test
    fun clearing_history_filter_removes_optional_keys() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)
        repo.setHistoryFilter(
            HistoryFilter(
                query = "head",
                startAfterEpochMillis = 1L,
                startBeforeEpochMillis = 2L,
                tags = setOf("Stress"),
            ),
        )

        repo.setHistoryFilter(HistoryFilter.Default)

        val restored = repo.historyFilter.first()
        assertEquals("", restored.query)
        assertNull(restored.startAfterEpochMillis)
        assertNull(restored.startBeforeEpochMillis)
        assertEquals(emptySet<String>(), restored.tags)
        assertEquals(HistorySort.DateDesc, restored.sort)
    }

    @Test
    fun inverted_severity_range_is_coerced_on_read() = runTest {
        val store = FakePreferencesDataStore()
        val repo = UiPreferencesRepository(store)
        repo.setHistoryFilter(HistoryFilter(minSeverity = 7, maxSeverity = 9))

        val restored = repo.historyFilter.first()
        assertEquals(7, restored.minSeverity)
        assertEquals(9, restored.maxSeverity)
    }
}

private class FakePreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())

    override val data = flow

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        // Datastore's built-in extension does the snapshot-copy for us.
        val mutable = flow.value.toMutablePreferences()
        val updated = transform(mutable)
        flow.value = updated
        return updated
    }
}

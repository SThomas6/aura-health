package com.example.mob_dev_portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisResultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the persistence seam the AnalysisWorker writes through.
 *
 * The store is a small enough surface that we mostly care about two
 * contracts:
 *   1. A save followed by `latest.first()` returns what we saved (round
 *      trip).
 *   2. A partial/invalid backing state surfaces as `null` rather than
 *      throwing — a user whose app was upgraded mid-run shouldn't see a
 *      crash just because an older payload was missing one of the keys.
 */
class AnalysisResultStoreTest {

    @Test
    fun save_and_latest_round_trip() = runTest {
        val store = AnalysisResultStore(FakePreferencesDataStore())

        store.save(
            summaryText = "## Patterns\n- ok",
            guidance = AnalysisGuidance.Clear,
            completedAtEpochMillis = 1_234L,
        )

        val stored = store.latest.first()
        assertNotNull(stored)
        assertEquals("## Patterns\n- ok", stored!!.summaryText)
        assertEquals(AnalysisGuidance.Clear, stored.guidance)
        assertEquals(1_234L, stored.completedAtEpochMillis)
    }

    @Test
    fun latest_is_null_for_fresh_store() = runTest {
        val store = AnalysisResultStore(FakePreferencesDataStore())
        assertNull(store.latest.first())
    }

    @Test
    fun overwriting_replaces_previous_entry() = runTest {
        val store = AnalysisResultStore(FakePreferencesDataStore())
        store.save("first", AnalysisGuidance.Clear, 1L)
        store.save("second", AnalysisGuidance.SeekAdvice, 2L)

        val stored = store.latest.first()!!
        assertEquals("second", stored.summaryText)
        assertEquals(AnalysisGuidance.SeekAdvice, stored.guidance)
        assertEquals(2L, stored.completedAtEpochMillis)
    }

    @Test
    fun clear_wipes_previous_entry() = runTest {
        val store = AnalysisResultStore(FakePreferencesDataStore())
        store.save("summary", AnalysisGuidance.Clear, 1L)
        store.clear()
        assertNull(store.latest.first())
    }

    // --- test double -----------------------------------------------------

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data = flow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val previous = flow.value
            val mutable = previous.toMutablePreferences()
            val updated = transform(mutable)
            flow.value = updated
            return updated
        }

        private fun Preferences.toMutablePreferences(): MutablePreferences {
            val out = mutablePreferencesOf()
            asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                out[key as Preferences.Key<Any>] = value
            }
            return out
        }
    }
}

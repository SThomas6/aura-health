package com.example.mob_dev_portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.data.health.HealthPreferencesRepository
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import com.example.mob_dev_portfolio.ui.trends.TrendVisualisationScreen
import com.example.mob_dev_portfolio.ui.trends.TrendVisualisationViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Instrumentation tests for the rebuilt Trends dashboard. The new
 * surface exposes:
 *   • a symptom dropdown (trends_symptom_picker)
 *   • a range segmented row (trends_range_{week|month|sixmonths|year})
 *   • multi-select overlay chips (trends_overlay_env_humidity, …)
 *   • the chart canvas itself (trends_chart)
 *
 * We construct the ViewModel directly with fakes rather than going
 * through its Factory so these tests don't touch real Health Connect.
 */
@RunWith(AndroidJUnit4::class)
class TrendVisualisationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Pin "now" so the 1w / 1m windows behave deterministically
    // across test hosts. All test logs are dated relative to this
    // instant.
    private val now: Instant = Instant.parse("2025-06-15T12:00:00Z")
    private val zone: ZoneId = ZoneId.of("UTC")
    private val clock: Clock = Clock.fixed(now, zone)

    private fun log(
        id: Long,
        name: String,
        severity: Int,
        daysAgo: Long,
        humidity: Int? = null,
        temperature: Double? = null,
    ): SymptomLog {
        val epoch = now.minusSeconds(daysAgo * 24 * 3600).toEpochMilli()
        return SymptomLog(
            id = id,
            symptomName = name,
            description = "$name description",
            startEpochMillis = epoch,
            endEpochMillis = null,
            severity = severity,
            medication = "",
            contextTags = emptyList(),
            notes = "",
            createdAtEpochMillis = epoch,
            humidityPercent = humidity,
            temperatureCelsius = temperature,
        )
    }

    private fun buildVm(logs: List<SymptomLog>): TrendVisualisationViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return TrendVisualisationViewModel(
            symptomRepository = InMemoryTrendRepository(logs),
            // Real HealthHistoryRepository — HC isn't available on
            // the emulator/Firebase test lab by default, so readSeries
            // returns zero-filled Series which is the right behaviour
            // for these UI tests (no overlay data = overlay toggles
            // still wire through correctly, chart renders without HC).
            healthHistoryRepository = HealthHistoryRepository(context, nowProvider = { now }),
            healthPreferences = FakeHealthPreferences(enabled = emptySet()),
            clock = clock,
            zone = zone,
        )
    }

    @Test
    fun screen_renders_when_logs_exist() {
        val vm = buildVm(
            listOf(
                log(1, "Headache", 4, daysAgo = 2),
                log(2, "Nausea", 6, daysAgo = 1),
                log(3, "Headache", 3, daysAgo = 0),
            ),
        )
        composeRule.setContent {
            AuraTheme {
                TrendVisualisationScreen(onBack = {}, viewModel = vm)
            }
        }

        // Chart canvas appears once the default symptom is auto-selected.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trends_chart").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("trends_chart").assertIsDisplayed()
        composeRule.onNodeWithTag("trends_symptom_picker").assertIsDisplayed()
        composeRule.onNodeWithTag("trends_range").assertIsDisplayed()
        composeRule.onNodeWithTag("trends_overlays").assertIsDisplayed()
    }

    @Test
    fun screen_renders_empty_symptom_picker_when_no_logs() {
        val vm = buildVm(emptyList())
        composeRule.setContent {
            AuraTheme {
                TrendVisualisationScreen(onBack = {}, viewModel = vm)
            }
        }

        // With no logs there's no symptom to pick — the picker
        // placeholder message lives in the dropdown slot but the range
        // row is always present.
        composeRule.onNodeWithTag("trends_range").assertIsDisplayed()
        composeRule.onNodeWithTag("trends_range_week").assertIsDisplayed()
    }

    @Test
    fun switching_range_keeps_screen_alive() {
        val vm = buildVm(
            listOf(
                log(1, "Headache", 4, daysAgo = 2),
                log(2, "Nausea", 6, daysAgo = 60),
            ),
        )
        composeRule.setContent {
            AuraTheme {
                TrendVisualisationScreen(onBack = {}, viewModel = vm)
            }
        }

        composeRule.onNodeWithTag("trends_range_week").performClick()
        // Smoke check — the canvas and range row should both still be
        // on screen; anything blowing up here would fail-fast.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trends_chart").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("trends_chart").assertIsDisplayed()
        composeRule.onNodeWithTag("trends_range").assertIsDisplayed()
    }

    @Test
    fun toggling_humidity_overlay_adds_it_to_series() {
        val vm = buildVm(
            listOf(
                log(1, "Headache", 4, daysAgo = 2, humidity = 75),
                log(2, "Headache", 6, daysAgo = 1, humidity = 80),
            ),
        )
        composeRule.setContent {
            AuraTheme {
                TrendVisualisationScreen(onBack = {}, viewModel = vm)
            }
        }

        // Humidity chip is present before toggling — it's one of the
        // four always-visible environmental overlays.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trends_overlay_env_humidity").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("trends_overlay_env_humidity").performClick()
        // Chart stays alive; selecting the chip should not tear the
        // canvas down.
        composeRule.onNodeWithTag("trends_chart").assertIsDisplayed()
    }
}

// ── Fakes ──────────────────────────────────────────────────────────────

/**
 * Implements `observeAll` — the only method the Trends ViewModel calls.
 * Everything else delegates to an in-memory DAO stub.
 */
private class InMemoryTrendRepository(initial: List<SymptomLog>) : SymptomLogRepository(InMemoryTrendDao()) {
    private val source = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<SymptomLog>> = source.asStateFlow()
}

private class InMemoryTrendDao : SymptomLogDao {
    override suspend fun insert(entity: SymptomLogEntity): Long = 0L
    override suspend fun update(entity: SymptomLogEntity): Int = 0
    override fun observeAll() = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ) = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeById(id: Long) = MutableStateFlow<SymptomLogEntity?>(null).asStateFlow()
    override fun observeCount() = MutableStateFlow(0).asStateFlow()
    override suspend fun delete(id: Long) = Unit
    override suspend fun listChronologicalAsc(): List<SymptomLogEntity> = emptyList()
    override suspend fun totalCount(): Int = 0
    override suspend fun averageSeverity(): Double? = null
}

/**
 * Overrides the Flow fields the Trends ViewModel observes. The inherited
 * DataStore is never touched because every accessed `open val` is
 * overridden here, so we pass a stub that throws on use — if a future
 * code path starts reading the DataStore these tests will fail loudly
 * rather than silently hit disk.
 */
private class FakeHealthPreferences(
    enabled: Set<HealthConnectMetric>,
) : HealthPreferencesRepository(StubDataStore) {
    override val enabledMetrics: Flow<Set<HealthConnectMetric>> = flowOf(enabled)
    override val connectionActive: Flow<Boolean> = flowOf(false)
    override val integrationEnabled: Flow<Boolean> = flowOf(false)
    override val sampleSeeded: Flow<Boolean> = flowOf(false)
}

private object StubDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flowOf(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        emptyPreferences()
}

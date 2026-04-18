package com.example.mob_dev_portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.ui.home.HomeScreen
import com.example.mob_dev_portfolio.ui.home.HomeViewModel
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HomeScreenInsightsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.of("UTC")
    private val fixedToday: LocalDate = LocalDate.of(2026, 4, 18)

    private fun log(id: Long, name: String, severity: Int, date: LocalDate): SymptomLog {
        val epoch = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return SymptomLog(
            id = id,
            symptomName = name,
            description = "description",
            startEpochMillis = epoch,
            endEpochMillis = null,
            severity = severity,
            medication = "",
            contextTags = emptyList(),
            notes = "",
            createdAtEpochMillis = epoch,
        )
    }

    @Test
    fun dashboard_renders_zero_state_when_no_logs() {
        val vm = HomeViewModel(
            InMemoryHomeRepository(emptyList()),
            todayProvider = { fixedToday },
            zone = zone,
        )
        composeRule.setContent {
            AuraTheme {
                HomeScreen(
                    onLogSymptomClick = {},
                    onViewHistoryClick = {},
                    onOpenLog = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithTag("insights_dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("stat_total").assertIsDisplayed()
        composeRule.onNodeWithTag("stat_avg_severity").assertIsDisplayed()
        composeRule.onNodeWithTag("stat_top_symptom").assertIsDisplayed()
        composeRule.onNodeWithTag("trend_chart").assertIsDisplayed()
    }

    @Test
    fun dashboard_renders_totals_and_top_symptom_when_logs_exist() {
        val vm = HomeViewModel(
            InMemoryHomeRepository(
                listOf(
                    log(1, "Headache", 5, fixedToday),
                    log(2, "Headache", 7, fixedToday.minusDays(1)),
                    log(3, "Nausea", 3, fixedToday.minusDays(2)),
                ),
            ),
            todayProvider = { fixedToday },
            zone = zone,
        )
        composeRule.setContent {
            AuraTheme {
                HomeScreen(
                    onLogSymptomClick = {},
                    onViewHistoryClick = {},
                    onOpenLog = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("Headache").assertIsDisplayed()
        composeRule.onNodeWithTag("trend_chart").assertIsDisplayed()
    }
}

private class InMemoryHomeRepository(initial: List<SymptomLog>) : SymptomLogRepository(HomeStubDao()) {
    private val source = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<SymptomLog>> = source.asStateFlow()
    override fun observeById(id: Long) = source.map { list -> list.firstOrNull { it.id == id } }
}

private class HomeStubDao : SymptomLogDao {
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
}

package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.ui.home.HomeInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HomeInsightsTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun log(
        id: Long,
        name: String,
        severity: Int,
        date: LocalDate,
    ): SymptomLog {
        val epoch = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return SymptomLog(
            id = id,
            symptomName = name,
            description = "",
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
    fun empty_logs_produce_zero_stats_and_empty_7_day_trend() {
        val today = LocalDate.of(2026, 4, 18)
        val insights = HomeInsights.compute(emptyList(), today = today, zone = zone)

        assertEquals(0, insights.totalLogs)
        assertNull(insights.averageSeverity)
        assertNull(insights.mostCommonSymptom)
        assertEquals(0, insights.mostCommonSymptomCount)
        assertFalse(insights.hasLogs)
        assertEquals(HomeInsights.TREND_WINDOW_DAYS, insights.trend.size)
        assertEquals(today.minusDays(6L), insights.trend.first().date)
        assertEquals(today, insights.trend.last().date)
        assertTrue(insights.trend.all { it.count == 0 })
        assertEquals(0, insights.trendPeak)
    }

    @Test
    fun total_and_average_severity_are_computed_from_all_logs() {
        val today = LocalDate.of(2026, 4, 18)
        val logs = listOf(
            log(1, "Headache", 4, today.minusDays(10)),
            log(2, "Headache", 6, today.minusDays(2)),
            log(3, "Nausea", 8, today.minusDays(1)),
        )

        val insights = HomeInsights.compute(logs, today = today, zone = zone)

        assertEquals(3, insights.totalLogs)
        assertNotNull(insights.averageSeverity)
        assertEquals(6.0, insights.averageSeverity!!, 0.0001)
        assertTrue(insights.hasLogs)
    }

    @Test
    fun most_common_symptom_picks_highest_count() {
        val today = LocalDate.of(2026, 4, 18)
        val logs = listOf(
            log(1, "Headache", 4, today),
            log(2, "Headache", 5, today.minusDays(1)),
            log(3, "Nausea", 6, today.minusDays(2)),
            log(4, "Headache", 7, today.minusDays(3)),
            log(5, "Fatigue", 3, today.minusDays(4)),
        )

        val insights = HomeInsights.compute(logs, today = today, zone = zone)

        assertEquals("Headache", insights.mostCommonSymptom)
        assertEquals(3, insights.mostCommonSymptomCount)
    }

    @Test
    fun trend_counts_only_last_seven_days_inclusive_of_today() {
        val today = LocalDate.of(2026, 4, 18)
        val logs = listOf(
            log(1, "A", 5, today),
            log(2, "A", 5, today),
            log(3, "B", 5, today.minusDays(3)),
            log(4, "B", 5, today.minusDays(6)),
            log(5, "C", 5, today.minusDays(7)),
            log(6, "D", 5, today.minusDays(30)),
        )

        val insights = HomeInsights.compute(logs, today = today, zone = zone)

        val counts = insights.trend.associate { it.date to it.count }
        assertEquals(2, counts[today])
        assertEquals(1, counts[today.minusDays(3)])
        assertEquals(1, counts[today.minusDays(6)])
        assertEquals(null, counts[today.minusDays(7)])
        assertEquals(2, insights.trendPeak)
    }

    @Test
    fun trend_orders_days_from_oldest_to_newest() {
        val today = LocalDate.of(2026, 4, 18)
        val insights = HomeInsights.compute(emptyList(), today = today, zone = zone)

        val dates = insights.trend.map { it.date }
        val sorted = dates.sorted()
        assertEquals(sorted, dates)
    }

    @Test
    fun symptom_names_are_trimmed_when_grouping() {
        val today = LocalDate.of(2026, 4, 18)
        val logs = listOf(
            log(1, "  Headache  ", 5, today),
            log(2, "Headache", 5, today.minusDays(1)),
            log(3, "Nausea", 5, today.minusDays(2)),
        )

        val insights = HomeInsights.compute(logs, today = today, zone = zone)

        assertEquals("Headache", insights.mostCommonSymptom)
        assertEquals(2, insights.mostCommonSymptomCount)
    }
}

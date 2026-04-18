package com.example.mob_dev_portfolio.ui.home

import com.example.mob_dev_portfolio.data.SymptomLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyCount(
    val date: LocalDate,
    val count: Int,
)

data class HomeInsights(
    val totalLogs: Int,
    val averageSeverity: Double?,
    val mostCommonSymptom: String?,
    val mostCommonSymptomCount: Int,
    val trend: List<DailyCount>,
) {
    val hasLogs: Boolean get() = totalLogs > 0
    val trendPeak: Int get() = trend.maxOfOrNull { it.count } ?: 0

    companion object {
        const val TREND_WINDOW_DAYS = 7

        fun compute(
            logs: List<SymptomLog>,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): HomeInsights {
            if (logs.isEmpty()) {
                return HomeInsights(
                    totalLogs = 0,
                    averageSeverity = null,
                    mostCommonSymptom = null,
                    mostCommonSymptomCount = 0,
                    trend = buildEmptyTrend(today),
                )
            }

            val avg = logs.sumOf { it.severity.toDouble() } / logs.size

            val grouped = logs
                .groupingBy { it.symptomName.trim().ifEmpty { "Unknown" } }
                .eachCount()
            val topEntry = grouped.maxByOrNull { it.value }

            val windowStart = today.minusDays((TREND_WINDOW_DAYS - 1).toLong())
            val byDay = logs.asSequence()
                .map { log -> Instant.ofEpochMilli(log.startEpochMillis).atZone(zone).toLocalDate() }
                .filter { date -> !date.isBefore(windowStart) && !date.isAfter(today) }
                .groupingBy { it }
                .eachCount()

            val trend = (0 until TREND_WINDOW_DAYS).map { offset ->
                val day = windowStart.plusDays(offset.toLong())
                DailyCount(date = day, count = byDay[day] ?: 0)
            }

            return HomeInsights(
                totalLogs = logs.size,
                averageSeverity = avg,
                mostCommonSymptom = topEntry?.key,
                mostCommonSymptomCount = topEntry?.value ?: 0,
                trend = trend,
            )
        }

        private fun buildEmptyTrend(today: LocalDate): List<DailyCount> {
            val start = today.minusDays((TREND_WINDOW_DAYS - 1).toLong())
            return (0 until TREND_WINDOW_DAYS).map { offset ->
                DailyCount(date = start.plusDays(offset.toLong()), count = 0)
            }
        }
    }
}

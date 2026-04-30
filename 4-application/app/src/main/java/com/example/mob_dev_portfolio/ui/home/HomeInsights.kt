package com.example.mob_dev_portfolio.ui.home

import com.example.mob_dev_portfolio.data.SymptomLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One bucket in the rolling 7-day trend strip rendered on the Home screen.
 * Anchored to a [LocalDate] (system zone) rather than an epoch so that
 * a log made at 23:55 local time falls into the same bucket the user
 * thinks of as "today" rather than rolling over via UTC.
 */
data class DailyCount(
    val date: LocalDate,
    val count: Int,
)

/**
 * Summary statistics displayed on the Home screen.
 *
 * Derived from a list of [SymptomLog]s rather than computed by the
 * database — the dataset is small (a single user's log history) so doing
 * the aggregation in Kotlin keeps the schema simple and avoids needing
 * separate Room queries for "average severity", "most common symptom",
 * and "trend by day". When/if the dataset grows, the Room layer can take
 * over without changing the Composable contract.
 *
 * The computation is pure: [compute] takes the clock and zone as
 * parameters so unit tests can inject a fixed `today` and assert the
 * trend window deterministically.
 */
data class HomeInsights(
    val totalLogs: Int,
    val averageSeverity: Double?,
    val mostCommonSymptom: String?,
    val mostCommonSymptomCount: Int,
    val trend: List<DailyCount>,
) {
    val hasLogs: Boolean get() = totalLogs > 0
    /**
     * Highest single-day count in the trend window. Used by the chart to
     * scale bar heights — `0` when the user has no logs in the window,
     * which the chart treats as the empty state.
     */
    val trendPeak: Int get() = trend.maxOfOrNull { it.count } ?: 0

    companion object {
        /** Length of the rolling trend window in days, including today. */
        const val TREND_WINDOW_DAYS = 7

        /**
         * Compute insights for a snapshot of [logs].
         *
         * `today` and `zone` are injected (rather than read from the
         * system clock here) so unit tests can pin time and assert the
         * trend window without flake. Logs whose start date falls outside
         * the window are still counted in [totalLogs] and the severity /
         * frequency stats — only the [trend] respects the window.
         */
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

            // Normalise blank/whitespace-only symptom names to "Unknown"
            // so empty strings (legacy entries from before validation
            // tightened) don't create their own group and skew the
            // "most common symptom" output.
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

            // Materialise every day in the window — even days with zero
            // logs need a [DailyCount] entry so the chart renders a flat
            // baseline rather than an irregular gap between dates.
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

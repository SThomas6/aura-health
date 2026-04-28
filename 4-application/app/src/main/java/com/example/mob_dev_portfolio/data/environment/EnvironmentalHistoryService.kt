package com.example.mob_dev_portfolio.data.environment

import java.time.Instant

/**
 * Fetches a time-series of environmental conditions (weather + AQI) for
 * the Trends dashboard's overlay lines.
 *
 * Separate from [EnvironmentalService] because the two call patterns are
 * different:
 *   • [EnvironmentalService] is "one call, now, at a point" — fires on
 *     symptom save, persists a single snapshot.
 *   • This service is "one call, many points, over a window" — fires when
 *     the Trends ViewModel needs continuous lines to overlay the chart.
 *
 * The implementation (Open-Meteo) picks the right upstream endpoint based
 * on how far back the `start` instant lies — recent windows use the forecast endpoint
 * with `past_days=N`, older windows use the dedicated archive endpoint.
 * That split is an implementation concern the caller should not see.
 */
interface EnvironmentalHistoryService {

    /**
     * Pull environmental samples between [start] (inclusive) and [end]
     * (exclusive). [granularity] controls whether the upstream returns
     * hourly or daily rows — Hourly only makes sense for sub-day ranges,
     * Daily for anything longer.
     */
    suspend fun fetchHistory(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: Granularity,
    ): HistoryResult

    enum class Granularity {
        /** One sample per hour. Use for Day-range charts. */
        Hourly,

        /** One sample per day (upstream returns mean-of-day). Use for week-and-up. */
        Daily,
    }
}

/**
 * A single time-series point. Every field is nullable because
 * Open-Meteo can drop individual fields on bad sensor days while the
 * row itself is still valid.
 */
data class EnvironmentalSample(
    val time: Instant,
    val temperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    val airQualityIndex: Int? = null,
)

sealed interface HistoryResult {
    /** Partial or complete data. Samples are sorted by [EnvironmentalSample.time]. */
    data class Success(val samples: List<EnvironmentalSample>) : HistoryResult

    /** No data available (offline, unknown location, upstream error). */
    data object Unavailable : HistoryResult
}

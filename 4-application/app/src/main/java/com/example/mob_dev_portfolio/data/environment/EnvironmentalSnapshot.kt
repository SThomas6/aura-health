package com.example.mob_dev_portfolio.data.environment

/**
 * Point-in-time environmental reading captured **once** at log save-time.
 *
 * All fields are nullable so a partial fetch (e.g. weather endpoint succeeds
 * but air-quality returns HTTP 503) still persists what we have. The row is
 * the source of truth — we never re-fetch on UI bind or on edit.
 */
data class EnvironmentalSnapshot(
    /** Open-Meteo WMO weather code (0 = clear, 61 = rain, etc.). */
    val weatherCode: Int? = null,
    /** Human-readable mapping of [weatherCode] — persisted so the WMO mapping
     *  is frozen at save time and doesn't shift if we update the lookup later. */
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    /** European AQI (lower is cleaner; 0–20 good, 80+ very poor). */
    val airQualityIndex: Int? = null,
) {
    val isEmpty: Boolean
        get() = weatherCode == null &&
            weatherDescription == null &&
            temperatureCelsius == null &&
            humidityPercent == null &&
            pressureHpa == null &&
            airQualityIndex == null

    companion object {
        val EMPTY: EnvironmentalSnapshot = EnvironmentalSnapshot()
    }
}

/**
 * Outcome of a single environmental fetch attempt. The VM maps each case to
 * a non-blocking Snackbar — the symptom save always proceeds regardless.
 */
sealed interface EnvironmentalFetchResult {
    /** Partial or complete data (any field may still be null). */
    data class Success(val snapshot: EnvironmentalSnapshot) : EnvironmentalFetchResult

    /** The 5-second SLA from `withTimeout(5000)` elapsed. */
    data object Timeout : EnvironmentalFetchResult

    /** DNS/socket failure — most commonly no internet. */
    data object NoNetwork : EnvironmentalFetchResult

    /** Upstream responded with an HTTP error (4xx/5xx) or malformed body. */
    data class ApiError(val message: String) : EnvironmentalFetchResult
}

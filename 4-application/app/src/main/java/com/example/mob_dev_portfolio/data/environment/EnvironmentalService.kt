package com.example.mob_dev_portfolio.data.environment

/**
 * Fetches weather + air-quality for a rounded coordinate pair. One call per
 * symptom save — never on UI bind or edit.
 *
 * Implementations must **not** throw `TimeoutCancellationException`: the
 * caller wraps the network work in `withTimeout(5_000)` and maps the
 * cancellation itself to [EnvironmentalFetchResult.Timeout]. All other
 * failure modes (no internet, HTTP error, parse error) must be translated
 * into the matching [EnvironmentalFetchResult] subtype so the save flow
 * never sees a raw exception.
 */
interface EnvironmentalService {
    suspend fun fetch(latitude: Double, longitude: Double): EnvironmentalFetchResult
}

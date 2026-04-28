package com.example.mob_dev_portfolio.data.environment

import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Caches + resolves historical environmental series for the Trends
 * dashboard.
 *
 * Three jobs in one class, deliberately kept small:
 *
 * 1. Ask the [EnvironmentalHistoryService] for raw samples over a window.
 *
 * 2. Pick a sensible (latitude, longitude) to hand it — we reuse the
 *    coordinate already persisted on the user's most-recent symptom log
 *    rather than opening a fresh location fix. That avoids a permission
 *    prompt just for viewing Trends, works offline on cached logs, and
 *    stays visually consistent with the "point-in-time snapshot"
 *    semantics the log-save path already uses.
 *
 * 3. Remember the answer in memory for the current session so repeated
 *    compositions (range clicks, overlay toggles, returning from another
 *    screen) don't re-hit the network.
 *
 * We don't persist the cache to disk — the trend is retrospective,
 * historical-weather isn't PII but it's also not worth the disk churn
 * for a feature the user glances at.
 */
class EnvironmentalHistoryRepository(
    private val service: EnvironmentalHistoryService,
    private val symptomLogRepository: SymptomLogRepository,
) {

    /**
     * Memoised response keyed on the shape of the request. Two reads
     * within the same session against the same (lat, lon, start, end,
     * granularity) return the cached samples, so the user can switch
     * symptoms or toggle overlays without refetching the sky.
     */
    private data class CacheKey(
        val latRounded: Double,
        val lonRounded: Double,
        val startEpoch: Long,
        val endEpoch: Long,
        val granularity: EnvironmentalHistoryService.Granularity,
    )

    private val cache = mutableMapOf<CacheKey, HistoryResult>()
    private val mutex = Mutex()

    /**
     * Pull samples for the given window. Returns [HistoryResult.Unavailable]
     * if no log has a saved location — the only way the trend graph can
     * know *where* the user lives is by reusing a saved log's coordinate,
     * and we intentionally don't prompt for location purely for this
     * screen.
     *
     * Buckets the start/end to the day to keep cache hit-rate high even
     * if the anchor instant jitters by a few seconds on each recompose.
     */
    suspend fun fetchHistory(
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
    ): HistoryResult {
        val (lat, lon) = latestLocation() ?: return HistoryResult.Unavailable
        // Round coords to 1dp (~11 km) so small GPS jitter between logs
        // doesn't bust the cache every session.
        val key = CacheKey(
            latRounded = round1dp(lat),
            lonRounded = round1dp(lon),
            startEpoch = start.epochSecond / BUCKET_KEY_SECONDS,
            endEpoch = end.epochSecond / BUCKET_KEY_SECONDS,
            granularity = granularity,
        )
        mutex.withLock { cache[key] }?.let { return it }

        val result = runCatching { service.fetchHistory(lat, lon, start, end, granularity) }
            .getOrElse { HistoryResult.Unavailable }
        mutex.withLock { cache[key] = result }
        return result
    }

    private suspend fun latestLocation(): Pair<Double, Double>? {
        val logs = symptomLogRepository.observeAll().firstOrNull() ?: return null
        return logs
            .sortedByDescending { it.startEpochMillis }
            .asSequence()
            .mapNotNull { log ->
                val lat = log.locationLatitude
                val lon = log.locationLongitude
                if (lat != null && lon != null) lat to lon else null
            }
            .firstOrNull()
    }

    private fun round1dp(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    companion object {
        // Cache key granularity — round the window endpoints to this many
        // seconds. One hour is fine: two recomposes 200ms apart hit the
        // same bucket, and the user actually switching to a different
        // range produces a fresh key.
        private const val BUCKET_KEY_SECONDS: Long = 3_600L
    }
}

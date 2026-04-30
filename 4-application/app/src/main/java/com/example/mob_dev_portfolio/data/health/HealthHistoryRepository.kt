package com.example.mob_dev_portfolio.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Reads Health Connect records and folds them into graph-ready time
 * series. Used by the Home dashboard's per-metric mini-charts and by the
 * fullscreen detail screen.
 *
 * The "aggregation" here is deliberately Kotlin-side rather than through
 * the HC SDK's native aggregate API. It's a single-shot read scoped to
 * the user-visible range (at most a year), which fits in memory easily,
 * and doing the bucketing ourselves lets the same query feed Day/Week/
 * Month/Year views without a new SDK call per range.
 */
class HealthHistoryRepository(
    private val context: Context,
    private val nowProvider: () -> Instant = { Instant.now() },
) {

    /** The time axes we bucket Health Connect reads across. */
    enum class Range(val days: Int, val bucket: Bucket) {
        /** Last 24 hours, bucketed hourly (24 buckets). */
        Day(days = 1, bucket = Bucket.Hour),

        /** Last 7 days, bucketed daily (default view). */
        Week(days = 7, bucket = Bucket.Day),

        /** Last 30 days, bucketed daily. */
        Month(days = 30, bucket = Bucket.Day),

        /** Last 180 days, bucketed weekly. Added for the Trends
         *  dashboard's 6-month view; the metric-detail screen doesn't
         *  expose this range yet but the bucketing works the same. */
        HalfYear(days = 180, bucket = Bucket.Week),

        /** Last 365 days, bucketed weekly. */
        Year(days = 365, bucket = Bucket.Week),
    }

    enum class Bucket(val stepSeconds: Long) {
        Hour(stepSeconds = SECONDS_PER_HOUR),
        Day(stepSeconds = SECONDS_PER_DAY),
        Week(stepSeconds = SECONDS_PER_WEEK),
    }

    /** A single point on a time-series chart. */
    data class DataPoint(
        val bucketStart: Instant,
        val value: Double,
    )

    /** Materialised series for one metric over one range. */
    data class Series(
        val metric: HealthConnectMetric,
        val range: Range,
        val points: List<DataPoint>,
        /** Sum / avg / max over the entire range — for the card headline. */
        val summary: Summary,
    )

    data class Summary(
        val total: Double?,
        val average: Double?,
        val minimum: Double?,
        val maximum: Double?,
        val latest: Double?,
    ) {
        companion object {
            val Empty = Summary(null, null, null, null, null)
        }
    }

    private val clientOrNull: HealthConnectClient? by lazy {
        runCatching {
            val status = HealthConnectClient.getSdkStatus(
                context,
                HealthConnectService.HEALTH_CONNECT_PACKAGE,
            )
            if (status == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context)
            else null
        }.onFailure { Log.w(TAG, "HC client unavailable", it) }.getOrNull()
    }

    /**
     * Read a single metric over a range. Returns a zero-filled series if
     * the permission isn't granted or the read fails — the caller
     * renders the chart with an empty-state overlay when every point's
     * value is zero.
     *
     * [endInstant] lets a caller pull a historical window (e.g. "last
     * week, anchored 7 days ago"). When omitted we default to the
     * repository's `nowProvider()`, so existing call sites get the
     * "up to now" behaviour they already expected.
     */
    suspend fun readSeries(
        metric: HealthConnectMetric,
        range: Range,
        endInstant: Instant? = null,
    ): Series {
        val client = clientOrNull ?: return emptySeries(metric, range, endInstant)

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (metric.readPermission !in granted) {
            return emptySeries(metric, range, endInstant)
        }

        val now = endInstant ?: nowProvider()
        val start = now.minus(Duration.ofDays(range.days.toLong()))
        val filter = TimeRangeFilter.between(start, now)

        return runCatching {
            when (metric) {
                HealthConnectMetric.Steps -> stepsSeries(client, filter, range, start, now)
                HealthConnectMetric.ActiveCaloriesBurned -> activeKcalSeries(client, filter, range, start, now)
                HealthConnectMetric.ExerciseSession -> exerciseSeries(client, filter, range, start, now)
                HealthConnectMetric.HeartRate -> heartRateSeries(client, filter, range, start, now)
                HealthConnectMetric.RestingHeartRate -> restingHrSeries(client, filter, range, start, now)
                HealthConnectMetric.OxygenSaturation -> spo2Series(client, filter, range, start, now)
                HealthConnectMetric.RespiratoryRate -> respRateSeries(client, filter, range, start, now)
                HealthConnectMetric.SleepSession -> sleepSeries(client, filter, range, start, now)
                HealthConnectMetric.Weight -> weightSeries(client, filter, range, start, now)
                HealthConnectMetric.Height -> heightSeries(client, filter, range, start, now)
                HealthConnectMetric.BodyFat -> bodyFatSeries(client, filter, range, start, now)
            }
        }.onFailure { Log.w(TAG, "readSeries($metric, $range) failed", it) }
            .getOrDefault(emptySeries(metric, range))
    }

    private fun emptySeries(metric: HealthConnectMetric, range: Range, endInstant: Instant? = null): Series {
        val end = endInstant ?: nowProvider()
        val start = end.minus(Duration.ofDays(range.days.toLong()))
        return Series(
            metric = metric,
            range = range,
            points = buckets(range, start, end).map { DataPoint(it, 0.0) },
            summary = Summary.Empty,
        )
    }

    // ── Record-type-specific aggregations ─────────────────────────────

    private suspend fun stepsSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(StepsRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val points = bucketSum(records, range, start, now) { it.startTime to it.count.toDouble() }
        val total = records.sumOf { it.count.toDouble() }.takeIf { it > 0 }
        return Series(
            metric = HealthConnectMetric.Steps,
            range = range,
            points = points,
            summary = Summary(
                total = total,
                average = total?.let { it / points.size },
                minimum = points.minOfOrNull { it.value }?.takeIf { it > 0 },
                maximum = points.maxOfOrNull { it.value }?.takeIf { it > 0 },
                latest = records.maxByOrNull { it.startTime }?.count?.toDouble(),
            ),
        )
    }

    private suspend fun activeKcalSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val points = bucketSum(records, range, start, now) { it.startTime to it.energy.inKilocalories }
        val total = records.sumOf { it.energy.inKilocalories }.takeIf { it > 0 }
        return Series(
            metric = HealthConnectMetric.ActiveCaloriesBurned,
            range = range,
            points = points,
            summary = Summary(
                total = total,
                average = total?.let { it / points.size },
                minimum = points.minOfOrNull { it.value }?.takeIf { it > 0 },
                maximum = points.maxOfOrNull { it.value }?.takeIf { it > 0 },
                latest = records.maxByOrNull { it.startTime }?.energy?.inKilocalories,
            ),
        )
    }

    private suspend fun exerciseSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val points = bucketCount(records, range, start, now) { it.startTime }
        return Series(
            metric = HealthConnectMetric.ExerciseSession,
            range = range,
            points = points,
            summary = Summary(
                total = points.sumOf { it.value }.takeIf { it > 0 },
                average = null,
                minimum = null,
                maximum = points.maxOfOrNull { it.value }?.takeIf { it > 0 },
                latest = null,
            ),
        )
    }

    private suspend fun heartRateSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val samples = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter))
            .records.excludingDemoSeedInProduction()
            .flatMap { r -> r.samples.map { s -> s.time to s.beatsPerMinute.toDouble() } }
        val points = bucketAvg(samples, range, start, now)
        val all = samples.map { it.second }
        return Series(
            metric = HealthConnectMetric.HeartRate,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = all.takeIf { it.isNotEmpty() }?.average(),
                minimum = all.minOrNull(),
                maximum = all.maxOrNull(),
                latest = samples.maxByOrNull { it.first }?.second,
            ),
        )
    }

    private suspend fun restingHrSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.beatsPerMinute.toDouble() }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.beatsPerMinute.toDouble() }
        return Series(
            metric = HealthConnectMetric.RestingHeartRate,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.beatsPerMinute?.toDouble(),
            ),
        )
    }

    private suspend fun spo2Series(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.percentage.value }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.percentage.value }
        return Series(
            metric = HealthConnectMetric.OxygenSaturation,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.percentage?.value,
            ),
        )
    }

    private suspend fun respRateSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.rate }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.rate }
        return Series(
            metric = HealthConnectMetric.RespiratoryRate,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.rate,
            ),
        )
    }

    private suspend fun sleepSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        // Sleep is anchored to the wake-up day so bars line up with the
        // day the user woke up on rather than the day they went to bed.
        val tuples = records.map { it.endTime to Duration.between(it.startTime, it.endTime).toMinutes().toDouble() }
        val points = bucketSumTuples(tuples, range, start, now)
        val values = tuples.map { it.second }
        val total = values.sum().takeIf { it > 0 }
        return Series(
            metric = HealthConnectMetric.SleepSession,
            range = range,
            points = points,
            summary = Summary(
                total = total,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = tuples.maxByOrNull { it.first }?.second,
            ),
        )
    }

    private suspend fun weightSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(WeightRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.weight.inKilograms }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.weight.inKilograms }
        return Series(
            metric = HealthConnectMetric.Weight,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.weight?.inKilograms,
            ),
        )
    }

    private suspend fun heightSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(HeightRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.height.inMeters }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.height.inMeters }
        return Series(
            metric = HealthConnectMetric.Height,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.height?.inMeters,
            ),
        )
    }

    private suspend fun bodyFatSeries(
        client: HealthConnectClient,
        filter: TimeRangeFilter,
        range: Range,
        start: Instant,
        now: Instant,
    ): Series {
        val records = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, filter))
            .records.excludingDemoSeedInProduction()
        val tuples = records.map { it.time to it.percentage.value }
        val points = bucketAvg(tuples, range, start, now)
        val values = records.map { it.percentage.value }
        return Series(
            metric = HealthConnectMetric.BodyFat,
            range = range,
            points = points,
            summary = Summary(
                total = null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
                minimum = values.minOrNull(),
                maximum = values.maxOrNull(),
                latest = records.maxByOrNull { it.time }?.percentage?.value,
            ),
        )
    }

    // ── Bucketing helpers ─────────────────────────────────────────────

    private fun buckets(range: Range, start: Instant, end: Instant): List<Instant> {
        val stepSeconds = range.bucket.stepSeconds
        val count = (Duration.between(start, end).seconds / stepSeconds).coerceAtLeast(1L).toInt() + 1
        // Round start *down* to the bucket boundary so points align with
        // calendar days / weeks / hours rather than the arbitrary instant
        // `now - 7d` resolved to.
        val zoned = start.atZone(ZoneId.systemDefault())
        val alignedStart = when (range.bucket) {
            Bucket.Hour -> zoned.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant()
            Bucket.Day -> zoned.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            Bucket.Week -> {
                val dayOfWeek = zoned.dayOfWeek.value - 1
                zoned.toLocalDate().minusDays(dayOfWeek.toLong())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()
            }
        }
        return (0 until count).map { alignedStart.plusSeconds(it * stepSeconds) }
    }

    private inline fun <T> bucketSum(
        records: List<T>,
        range: Range,
        start: Instant,
        end: Instant,
        extract: (T) -> Pair<Instant, Double>,
    ): List<DataPoint> {
        val bucketStarts = buckets(range, start, end)
        val sums = DoubleArray(bucketStarts.size)
        records.forEach { r ->
            val (time, value) = extract(r)
            val idx = indexOfBucket(bucketStarts, time)
            if (idx in sums.indices) sums[idx] += value
        }
        return bucketStarts.mapIndexed { i, t -> DataPoint(t, sums[i]) }
    }

    private fun bucketSumTuples(
        tuples: List<Pair<Instant, Double>>,
        range: Range,
        start: Instant,
        end: Instant,
    ): List<DataPoint> = bucketSum(tuples, range, start, end) { it }

    private inline fun <T> bucketCount(
        records: List<T>,
        range: Range,
        start: Instant,
        end: Instant,
        extract: (T) -> Instant,
    ): List<DataPoint> {
        val bucketStarts = buckets(range, start, end)
        val counts = IntArray(bucketStarts.size)
        records.forEach { r ->
            val idx = indexOfBucket(bucketStarts, extract(r))
            if (idx in counts.indices) counts[idx]++
        }
        return bucketStarts.mapIndexed { i, t -> DataPoint(t, counts[i].toDouble()) }
    }

    private fun bucketAvg(
        tuples: List<Pair<Instant, Double>>,
        range: Range,
        start: Instant,
        end: Instant,
    ): List<DataPoint> {
        val bucketStarts = buckets(range, start, end)
        val sums = DoubleArray(bucketStarts.size)
        val counts = IntArray(bucketStarts.size)
        tuples.forEach { (time, value) ->
            val idx = indexOfBucket(bucketStarts, time)
            if (idx in sums.indices) {
                sums[idx] += value
                counts[idx]++
            }
        }
        return bucketStarts.mapIndexed { i, t ->
            DataPoint(t, if (counts[i] == 0) 0.0 else sums[i] / counts[i])
        }
    }

    private fun indexOfBucket(bucketStarts: List<Instant>, time: Instant): Int {
        if (bucketStarts.isEmpty() || time.isBefore(bucketStarts.first())) return -1
        // Buckets are evenly spaced and sorted — binary-search for speed.
        var lo = 0
        var hi = bucketStarts.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (!bucketStarts[mid].isAfter(time)) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        private const val TAG = "HealthHistoryRepo"

        // Named time-axis constants — the bucket sizes drive both the
        // alignment maths in [buckets] and the index lookup in
        // [indexOfBucket], so giving them names instead of bare literals
        // makes "why is this 86_400" obvious at every call site.
        private const val SECONDS_PER_HOUR: Long = 3_600L
        private const val SECONDS_PER_DAY: Long = 24L * SECONDS_PER_HOUR
        private const val SECONDS_PER_WEEK: Long = 7L * SECONDS_PER_DAY
    }
}

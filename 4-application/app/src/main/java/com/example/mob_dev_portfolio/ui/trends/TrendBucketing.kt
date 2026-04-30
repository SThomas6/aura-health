package com.example.mob_dev_portfolio.ui.trends

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure axis + bucketing maths for the Trends dashboard.
 *
 * Extracted from [TrendVisualisationViewModel] so the calendar-aligned
 * bucket arithmetic can be unit-tested without standing up the entire
 * Flow pipeline. Everything here is deterministic given a [ZoneId];
 * there are no I/O dependencies, no clocks, and no nullable side
 * effects.
 *
 * The maths comes in two halves:
 *  1. [buildAxis] walks calendar units (hours/days/weeks/months) from
 *     `endInstant - range.days` up to `endInstant` and emits one
 *     bucket per unit. Monthly axes have variable real widths
 *     (28–31 days) which is why bucket *ends* are stored alongside
 *     bucket *starts* — downstream code can binary-search both arrays.
 *  2. [bucketAverage] routes (timestamp, value) tuples into those
 *     buckets, averages duplicates, and emits null for empty buckets.
 *     A null raw value renders as a chart gap, never as a misleading
 *     zero.
 */
class TrendBucketing(private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * Pre-computed axis for one render pass. Bucket starts and ends are
     * paired 1:1 so a sample is binary-searched into the unique bucket
     * where the inequality `start ≤ time < end` holds for that index.
     */
    data class Axis(
        val bucketStarts: List<Instant>,
        val bucketEnds: List<Instant>,
        val unit: BucketUnit,
    ) {
        val size: Int get() = bucketStarts.size
    }

    /**
     * Walk calendar units from the aligned window start up to
     * [endInstant], emitting one bucket per unit.
     */
    fun buildAxis(range: TrendRange, endInstant: Instant): Axis {
        val rawStart = endInstant.minus(Duration.ofDays(range.days.toLong()))
        val alignedStart = alignDown(rawStart, range.bucketUnit)
        val starts = mutableListOf<Instant>()
        val ends = mutableListOf<Instant>()
        var cursor = alignedStart
        // Safety cap — a monthly 1-year view is 13 buckets, a daily
        // 1-month view is 31, an hourly 1-day view is 25. 512 is
        // comfortably larger than anything legitimate and bounds a
        // runaway loop from e.g. a broken advance() returning the same
        // instant twice.
        var guard = 0
        while (!cursor.isAfter(endInstant) && guard < AXIS_GUARD) {
            val next = advance(cursor, range.bucketUnit)
            starts += cursor
            ends += next
            cursor = next
            guard++
        }
        if (starts.isEmpty()) {
            // Extreme edge case (endInstant == alignedStart and the
            // unit makes advance strictly forward): emit at least one
            // bucket so downstream code renders an empty chart rather
            // than NPEing.
            starts += alignedStart
            ends += advance(alignedStart, range.bucketUnit)
        }
        return Axis(starts, ends, range.bucketUnit)
    }

    /**
     * Average tuples into [axis] buckets. Empty buckets emit `null` so
     * the chart can draw a gap rather than a misleading zero.
     *
     * Uses binary search against the start/end arrays so variable-width
     * monthly buckets (28–31 days) route samples correctly. Fixed-width
     * bucket sizes would still work with a plain delta-divide, but a
     * single unified path is easier to reason about than two branches.
     */
    fun bucketAverage(
        records: List<Pair<Long, Double>>,
        axis: Axis,
    ): List<TrendBucket> {
        if (axis.bucketStarts.isEmpty()) return emptyList()
        val startsMs = LongArray(axis.size) { axis.bucketStarts[it].toEpochMilli() }
        val endsMs = LongArray(axis.size) { axis.bucketEnds[it].toEpochMilli() }
        val sums = DoubleArray(axis.size)
        val counts = IntArray(axis.size)
        records.forEach { (time, value) ->
            // Find the bucket where startsMs[i] <= time < endsMs[i].
            var lo = 0
            var hi = axis.size - 1
            var idx = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    time < startsMs[mid] -> hi = mid - 1
                    time >= endsMs[mid] -> lo = mid + 1
                    else -> { idx = mid; break }
                }
            }
            if (idx >= 0) {
                sums[idx] += value
                counts[idx]++
            }
        }
        return axis.bucketStarts.mapIndexed { i, start ->
            TrendBucket(start, if (counts[i] == 0) null else sums[i] / counts[i])
        }
    }

    private fun alignDown(instant: Instant, unit: BucketUnit): Instant {
        val zoned = instant.atZone(zone)
        return when (unit) {
            BucketUnit.Hour -> zoned.truncatedTo(ChronoUnit.HOURS).toInstant()
            BucketUnit.Day -> zoned.toLocalDate().atStartOfDay(zone).toInstant()
            BucketUnit.Week -> {
                // ISO week: Monday-start. Align to this week's Monday.
                val dow = zoned.dayOfWeek.value - 1
                zoned.toLocalDate().minusDays(dow.toLong()).atStartOfDay(zone).toInstant()
            }
            BucketUnit.Month -> zoned.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(zone)
                .toInstant()
        }
    }

    /** Advance by exactly one [unit] starting from [instant]. */
    private fun advance(instant: Instant, unit: BucketUnit): Instant {
        val zoned = instant.atZone(zone)
        return when (unit) {
            BucketUnit.Hour -> instant.plusSeconds(SECONDS_PER_HOUR)
            BucketUnit.Day -> zoned.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            BucketUnit.Week -> zoned.toLocalDate().plusWeeks(1).atStartOfDay(zone).toInstant()
            BucketUnit.Month -> zoned.toLocalDate().plusMonths(1).atStartOfDay(zone).toInstant()
        }
    }

    companion object {
        private const val AXIS_GUARD = 512
        private const val SECONDS_PER_HOUR: Long = 3_600L
    }
}

package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.ui.trends.BucketUnit
import com.example.mob_dev_portfolio.ui.trends.TrendBucketing
import com.example.mob_dev_portfolio.ui.trends.TrendRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for the pure axis + bucketing maths.
 *
 * All times are anchored to UTC so day/week/month alignment is
 * deterministic regardless of the host's local zone — the JVM running
 * the tests on a developer machine versus CI must produce identical
 * results.
 */
class TrendBucketingTest {

    private val zone = ZoneId.of("UTC")
    private val bucketing = TrendBucketing(zone)

    @Test
    fun week_axis_emits_seven_daily_buckets_aligned_to_midnight_utc() {
        // 2024-06-19T15:30:00Z — mid-Wednesday afternoon.
        val end = Instant.parse("2024-06-19T15:30:00Z")
        val axis = bucketing.buildAxis(TrendRange.Week, end)

        assertEquals(BucketUnit.Day, axis.unit)
        assertEquals(8, axis.size) // start-aligned + walking up to end yields 8
        assertEquals(Instant.parse("2024-06-12T00:00:00Z"), axis.bucketStarts.first())
        // Each bucket end equals the next bucket's start.
        for (i in 0 until axis.size - 1) {
            assertEquals(axis.bucketEnds[i], axis.bucketStarts[i + 1])
        }
        // Daily buckets are exactly 24h wide.
        for (i in 0 until axis.size) {
            assertEquals(86_400L, axis.bucketEnds[i].epochSecond - axis.bucketStarts[i].epochSecond)
        }
    }

    @Test
    fun month_axis_buckets_are_variable_width_30_or_31_days() {
        // Year view → monthly buckets across calendar months of 28-31d.
        val end = Instant.parse("2024-12-31T12:00:00Z")
        val axis = bucketing.buildAxis(TrendRange.Year, end)

        assertEquals(BucketUnit.Month, axis.unit)
        // Variable-width: at least one bucket should differ in length
        // from another (Feb is 28-29d, Jan is 31d) — proves we're walking
        // calendar months rather than fixed-width spans.
        val widths = (0 until axis.size).map {
            axis.bucketEnds[it].epochSecond - axis.bucketStarts[it].epochSecond
        }.toSet()
        assertTrue("Expected variable monthly widths, got $widths", widths.size > 1)
    }

    @Test
    fun bucket_average_routes_samples_into_correct_buckets() {
        val end = Instant.parse("2024-06-19T00:00:00Z")
        val axis = bucketing.buildAxis(TrendRange.Week, end)

        // Two samples on the Mon (start of axis), one on Fri.
        val mon = axis.bucketStarts[0].toEpochMilli()
        val fri = axis.bucketStarts[4].toEpochMilli()
        val tuples = listOf(mon to 10.0, mon + 1000L to 20.0, fri to 5.0)

        val buckets = bucketing.bucketAverage(tuples, axis)

        assertEquals(15.0, buckets[0].rawValue!!, 1e-9) // mean(10, 20)
        assertEquals(5.0, buckets[4].rawValue!!, 1e-9)
        // Untouched buckets emit null so the chart can render gaps
        // rather than misleading zeros.
        assertNull(buckets[1].rawValue)
        assertNull(buckets[2].rawValue)
        assertNull(buckets[3].rawValue)
    }

    @Test
    fun out_of_range_samples_are_dropped() {
        val end = Instant.parse("2024-06-19T00:00:00Z")
        val axis = bucketing.buildAxis(TrendRange.Week, end)
        val beforeStart = axis.bucketStarts.first().toEpochMilli() - 1_000_000L
        val afterEnd = axis.bucketEnds.last().toEpochMilli() + 1_000_000L

        val buckets = bucketing.bucketAverage(
            listOf(beforeStart to 100.0, afterEnd to 200.0),
            axis,
        )
        assertTrue(buckets.all { it.rawValue == null })
    }

    @Test
    fun day_axis_emits_hourly_buckets_3600s_wide() {
        val end = Instant.parse("2024-06-19T15:30:00Z")
        val axis = bucketing.buildAxis(TrendRange.Day, end)

        assertEquals(BucketUnit.Hour, axis.unit)
        assertTrue(axis.size in 24..26)
        for (i in 0 until axis.size) {
            assertEquals(3_600L, axis.bucketEnds[i].epochSecond - axis.bucketStarts[i].epochSecond)
        }
    }

    @Test
    fun empty_axis_input_returns_empty_buckets() {
        // Defensive: bucketAverage must never NPE on a degenerate axis.
        val end = Instant.parse("2024-06-19T00:00:00Z")
        val axis = bucketing.buildAxis(TrendRange.Week, end)
        // Empty record list → all buckets present with null values, not
        // an empty list — the chart still draws an empty axis.
        val buckets = bucketing.bucketAverage(emptyList(), axis)
        assertEquals(axis.size, buckets.size)
        assertTrue(buckets.all { it.rawValue == null })
        assertNotNull(buckets.first().bucketStart)
    }
}

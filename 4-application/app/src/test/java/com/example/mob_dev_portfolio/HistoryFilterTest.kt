package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import com.example.mob_dev_portfolio.ui.log.LogValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {

    @Test
    fun default_filter_has_no_active_filters() {
        val filter = HistoryFilter.Default
        assertFalse(filter.hasActiveFilters)
        assertFalse(filter.hasQuery)
        assertFalse(filter.hasSeverityBound)
        assertFalse(filter.hasDateBound)
        assertFalse(filter.hasTagFilter)
        assertEquals(HistorySort.DateDesc, filter.sort)
        assertEquals(LogValidator.MIN_SEVERITY, filter.minSeverity)
        assertEquals(LogValidator.MAX_SEVERITY, filter.maxSeverity)
    }

    @Test
    fun non_blank_query_is_active() {
        val filter = HistoryFilter(query = "headache")
        assertTrue(filter.hasQuery)
        assertTrue(filter.hasActiveFilters)
    }

    @Test
    fun whitespace_only_query_is_not_active() {
        val filter = HistoryFilter(query = "   ")
        assertFalse(filter.hasQuery)
        assertFalse(filter.hasActiveFilters)
    }

    @Test
    fun narrowed_severity_range_marks_filter_active() {
        val narrowed = HistoryFilter(minSeverity = 3, maxSeverity = 7)
        assertTrue(narrowed.hasSeverityBound)
        assertTrue(narrowed.hasActiveFilters)
    }

    @Test
    fun full_severity_range_is_not_active() {
        val full = HistoryFilter(
            minSeverity = LogValidator.MIN_SEVERITY,
            maxSeverity = LogValidator.MAX_SEVERITY,
        )
        assertFalse(full.hasSeverityBound)
    }

    @Test
    fun sort_alone_does_not_count_as_active_filter() {
        val sortedOnly = HistoryFilter(sort = HistorySort.NameAsc)
        assertFalse(sortedOnly.hasActiveFilters)
    }

    @Test
    fun date_bound_or_tag_filter_marks_active() {
        assertTrue(HistoryFilter(startAfterEpochMillis = 1L).hasActiveFilters)
        assertTrue(HistoryFilter(startBeforeEpochMillis = 1L).hasActiveFilters)
        assertTrue(HistoryFilter(tags = setOf("Stress")).hasActiveFilters)
    }

    @Test
    fun sort_fromName_roundtrips() {
        HistorySort.entries.forEach { sort ->
            assertEquals(sort, HistorySort.fromName(sort.name))
        }
        assertEquals(HistorySort.Default, HistorySort.fromName(null))
        assertEquals(HistorySort.Default, HistorySort.fromName("NOT_A_SORT"))
    }
}

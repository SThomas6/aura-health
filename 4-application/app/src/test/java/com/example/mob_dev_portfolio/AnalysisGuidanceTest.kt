package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the logic that picks the two-bucket clinical guidance shown on the
 * notification headline. If the regex or fallback threshold drifts, users
 * see a lock-screen notification that disagrees with what they read when
 * they tap through — so the mapping is covered exhaustively.
 */
class AnalysisGuidanceTest {

    @Test
    fun marker_clear_produces_clear() {
        val g = AnalysisGuidance.fromSummary("GUIDANCE: clear\n## Patterns\n- ok", maxSeverityInRecentLogs = 10)
        // Even with severe logs, an explicit model marker wins.
        assertEquals(AnalysisGuidance.Clear, g)
    }

    @Test
    fun marker_seek_medical_advice_produces_seek_advice() {
        val g = AnalysisGuidance.fromSummary("GUIDANCE: seek medical advice", maxSeverityInRecentLogs = 0)
        assertEquals(AnalysisGuidance.SeekAdvice, g)
    }

    @Test
    fun marker_with_dash_separator_is_recognised() {
        val g = AnalysisGuidance.fromSummary("Guidance - Seek advice", maxSeverityInRecentLogs = 0)
        assertEquals(AnalysisGuidance.SeekAdvice, g)
    }

    @Test
    fun marker_case_insensitive() {
        val g = AnalysisGuidance.fromSummary("guidance: CLEAR", maxSeverityInRecentLogs = 0)
        assertEquals(AnalysisGuidance.Clear, g)
    }

    @Test
    fun fallback_severity_at_or_above_7_seeks_advice() {
        val g = AnalysisGuidance.fromSummary("no marker here", maxSeverityInRecentLogs = 7)
        assertEquals(AnalysisGuidance.SeekAdvice, g)
    }

    @Test
    fun fallback_severity_below_7_stays_clear() {
        val g = AnalysisGuidance.fromSummary("no marker here", maxSeverityInRecentLogs = 6)
        assertEquals(AnalysisGuidance.Clear, g)
    }

    @Test
    fun empty_summary_uses_only_severity_fallback() {
        assertEquals(AnalysisGuidance.Clear, AnalysisGuidance.fromSummary("", 0))
        assertEquals(AnalysisGuidance.SeekAdvice, AnalysisGuidance.fromSummary("", 10))
    }

    @Test
    fun unknown_marker_phrase_falls_through_to_severity() {
        // Model said "Guidance: possibly" which doesn't match either bucket;
        // we fall through to severity.
        val g = AnalysisGuidance.fromSummary("GUIDANCE: possibly\n## notes", maxSeverityInRecentLogs = 8)
        assertEquals(AnalysisGuidance.SeekAdvice, g)
    }

    @Test
    fun headlines_are_distinct_and_non_empty() {
        // Guard against a future refactor accidentally making both copies
        // identical — the whole point of the enum is to be glanceable.
        assertEquals(2, AnalysisGuidance.values().map { it.headline }.toSet().size)
        AnalysisGuidance.values().forEach { g ->
            assert(g.headline.isNotBlank()) { "${g.name} headline was blank" }
            assert(g.bodyHint.isNotBlank()) { "${g.name} bodyHint was blank" }
        }
    }
}

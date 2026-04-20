package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.ai.AnalysisSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pins the DOB → age-range mapping and the name-redaction logic.
 *
 * These two behaviours are what the story's privacy acceptance criterion
 * hinges on: "payload sent to the Gemini API contains an age range (not a
 * specific DOB) and no names". If this file ever goes red, the PII guard
 * has leaked — nothing else needs to be diagnosed first.
 */
class AnalysisSanitizerTest {

    @Test
    fun null_dob_yields_unknown() {
        assertEquals(
            AnalysisSanitizer.UNKNOWN_AGE_RANGE,
            AnalysisSanitizer.ageRange(dobEpochMillis = null, nowEpochMillis = utc(2026, 4, 19)),
        )
    }

    @Test
    fun future_dob_yields_unknown() {
        // A DOB in the future shouldn't be coerced to "Under 18" — that would
        // mask clock-skew bugs and report implausible ages to the model.
        val now = utc(2026, 4, 19)
        val future = utc(2030, 1, 1)
        assertEquals(AnalysisSanitizer.UNKNOWN_AGE_RANGE, AnalysisSanitizer.ageRange(future, now))
    }

    @Test
    fun seventeen_year_old_is_under_18() {
        val now = utc(2026, 4, 19)
        val dob = utc(2008, 5, 1) // ~ 17y 11m
        assertEquals("Under 18", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun eighteen_year_old_is_18_to_24() {
        val now = utc(2026, 4, 19)
        val dob = utc(2008, 4, 19)
        assertEquals("18-24", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun thirty_year_old_is_25_to_34() {
        val now = utc(2026, 4, 19)
        val dob = utc(1996, 1, 1)
        assertEquals("25-34", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun sixty_five_year_old_is_65_plus() {
        val now = utc(2026, 4, 19)
        val dob = utc(1961, 1, 1)
        assertEquals("65+", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun birthday_tomorrow_still_counts_as_previous_bracket() {
        // Edge case: user turns 25 in 1 day. Until the day itself, they're 24.
        val now = utc(2026, 4, 19)
        val dob = utc(2001, 4, 20)
        assertEquals("18-24", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun birthday_today_advances_to_next_bracket() {
        val now = utc(2026, 4, 19)
        val dob = utc(2001, 4, 19)
        assertEquals("25-34", AnalysisSanitizer.ageRange(dob, now))
    }

    @Test
    fun strip_names_removes_full_name_case_insensitive() {
        val input = "JANE came to clinic; jane reported a headache."
        val redacted = AnalysisSanitizer.stripNames(input, listOf("Jane"))
        assertEquals(
            "${AnalysisSanitizer.REDACTED} came to clinic; ${AnalysisSanitizer.REDACTED} reported a headache.",
            redacted,
        )
    }

    @Test
    fun strip_names_splits_multi_token_name() {
        // "Jane Mary Doe" should knock out all three tokens anywhere they appear,
        // not just the exact three-word sequence.
        val input = "Patient Doe (Jane) complained about Mary's chair."
        val redacted = AnalysisSanitizer.stripNames(input, listOf("Jane Mary Doe"))
        val r = AnalysisSanitizer.REDACTED
        assertEquals("Patient $r ($r) complained about $r's chair.", redacted)
    }

    @Test
    fun strip_names_ignores_short_tokens() {
        // Single letters or "J" would over-match — skip anything below 2 chars
        // so we don't turn "Jo likes jam" into "[redacted]o likes [redacted]am".
        val input = "J is a letter, Jo is a name."
        val redacted = AnalysisSanitizer.stripNames(input, listOf("J", "Jo"))
        assertEquals("J is a letter, ${AnalysisSanitizer.REDACTED} is a name.", redacted)
    }

    @Test
    fun strip_names_respects_word_boundaries() {
        // "Ann" in "Annual" must NOT match — we only replace whole words.
        val input = "Ann is excited about the Annual meeting."
        val redacted = AnalysisSanitizer.stripNames(input, listOf("Ann"))
        assertEquals("${AnalysisSanitizer.REDACTED} is excited about the Annual meeting.", redacted)
    }

    @Test
    fun strip_profile_names_is_noop_when_no_name_set() {
        val input = "No name configured yet."
        assertEquals(input, AnalysisSanitizer.stripProfileNames(input, fullName = null))
        assertEquals(input, AnalysisSanitizer.stripProfileNames(input, fullName = ""))
        assertEquals(input, AnalysisSanitizer.stripProfileNames(input, fullName = "   "))
    }

    private fun utc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day)
        return cal.timeInMillis
    }
}

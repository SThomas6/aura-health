package com.example.mob_dev_portfolio.data.ai

import java.util.Calendar
import java.util.TimeZone

/**
 * PII scrubber for outbound Gemini payloads.
 *
 * Two responsibilities:
 *   1. [ageRange] — turns an exact DOB into a coarse bucket ("25-34") so the
 *      model sees age-appropriate context without the birthdate itself.
 *   2. [stripNames] — removes whole-word occurrences of the user's name (and
 *      any other flagged tokens) from any free-text field before it leaves
 *      the device.
 *
 * Kept pure & deterministic so unit tests can pin the mapping exactly — this
 * is the code path that enforces the story's "no PII leaves the device"
 * non-functional requirement and is the one reviewers are most likely to
 * inspect.
 */
object AnalysisSanitizer {

    const val UNKNOWN_AGE_RANGE = "Unknown"

    /**
     * Standard US-census-style buckets. Wide enough that a 30-year-old and a
     * 32-year-old land in the same "25-34" bin, which is the point — we want
     * correlations learned at cohort granularity, not per-birthdate.
     */
    private val BRACKETS: List<AgeBracket> = listOf(
        AgeBracket(min = 0, max = 17, label = "Under 18"),
        AgeBracket(min = 18, max = 24, label = "18-24"),
        AgeBracket(min = 25, max = 34, label = "25-34"),
        AgeBracket(min = 35, max = 44, label = "35-44"),
        AgeBracket(min = 45, max = 54, label = "45-54"),
        AgeBracket(min = 55, max = 64, label = "55-64"),
        AgeBracket(min = 65, max = Int.MAX_VALUE, label = "65+"),
    )

    /**
     * Converts a DOB timestamp to a bracket label. Uses UTC to avoid locale
     * drift — a user born at 11pm on the 31st shouldn't flip buckets based on
     * the device's current zone.
     */
    fun ageRange(dobEpochMillis: Long?, nowEpochMillis: Long): String {
        if (dobEpochMillis == null) return UNKNOWN_AGE_RANGE
        if (dobEpochMillis > nowEpochMillis) return UNKNOWN_AGE_RANGE
        val years = yearsBetween(dobEpochMillis, nowEpochMillis)
        if (years < 0) return UNKNOWN_AGE_RANGE
        return BRACKETS.firstOrNull { years in it.min..it.max }?.label ?: UNKNOWN_AGE_RANGE
    }

    /**
     * Replaces whole-word matches of any token in [names] with `[redacted]`.
     * Case-insensitive. Each token is split on whitespace so passing
     * "Jane Mary Doe" redacts "Jane", "Mary", AND "Doe" wherever they
     * appear — not just the full string.
     *
     * Empty / blank tokens are ignored so an unset profile doesn't turn into
     * a regex that matches everything.
     */
    fun stripNames(text: String, names: Collection<String>): String {
        if (text.isEmpty()) return text
        // De-dupe first (case-insensitively), THEN sort by length desc. A
        // TreeSet with a length-only comparator would collapse distinct
        // same-length tokens ("Jane" vs "Mary" both match at length 4) and
        // silently leak one of them — exactly the kind of subtle PII bug
        // the regression test `strip_names_splits_multi_token_name` pins.
        val tokens = names
            .asSequence()
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .map { it.trim() }
            .filter { it.length >= 2 } // single letters would over-match
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
            .map { Regex.escape(it) }
            .toList()
        if (tokens.isEmpty()) return text
        val pattern = Regex("\\b(${tokens.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
        return pattern.replace(text, REDACTED)
    }

    /**
     * Convenience: applies [stripNames] to a profile's `fullName` if present.
     * When the profile has no name, this is a no-op.
     */
    fun stripProfileNames(text: String, fullName: String?): String {
        if (fullName.isNullOrBlank()) return text
        return stripNames(text, listOf(fullName))
    }

    private fun yearsBetween(dobMillis: Long, nowMillis: Long): Int {
        val dob = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dobMillis }
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        var years = now.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        // Not yet had birthday this year? subtract one.
        val monthDiff = now.get(Calendar.MONTH) - dob.get(Calendar.MONTH)
        val dayDiff = now.get(Calendar.DAY_OF_MONTH) - dob.get(Calendar.DAY_OF_MONTH)
        if (monthDiff < 0 || (monthDiff == 0 && dayDiff < 0)) years -= 1
        return years
    }

    private data class AgeBracket(val min: Int, val max: Int, val label: String)

    const val REDACTED = "[redacted]"
}

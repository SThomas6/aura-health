package com.example.mob_dev_portfolio.ui.history

import com.example.mob_dev_portfolio.ui.log.LogValidator

/**
 * Sort orderings exposed in the History filter sheet.
 *
 * The display [label] for each entry is intentionally verbose ("Newest
 * first (most recently ended)") so the user understands that the
 * "newest" anchor is the END date rather than the start. This matters
 * because a multi-day symptom can start days before it actually ends —
 * users who are reviewing what's been happening recently want the most
 * recently CONCLUDED entries surfaced first.
 *
 * Persisted by name via [fromName] / [name] through
 * `UiPreferencesRepository`, so adding a new entry requires no migration
 * — but renaming an existing entry would silently invalidate stored
 * preferences and fall back to [Default].
 */
enum class HistorySort {
    DateDesc,
    DateAsc,
    SeverityDesc,
    SeverityAsc,
    NameAsc;

    val label: String
        get() = when (this) {
            // "Newest" / "Oldest" semantics are based on END date so that
            // a symptom that started weeks ago but only finished today
            // ranks ahead of one that started yesterday and ended last
            // night. Ongoing logs (no end date) fall back to their start
            // time and are surfaced in their own section by the History
            // screen, so within "Ended symptoms" the order here is the
            // one that drives the visual.
            DateDesc -> "Newest first (most recently ended)"
            DateAsc -> "Oldest first (least recently ended)"
            SeverityDesc -> "Severity high to low"
            SeverityAsc -> "Severity low to high"
            NameAsc -> "Symptom A–Z"
        }

    companion object {
        val Default: HistorySort = DateDesc
        fun fromName(raw: String?): HistorySort =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: Default
    }
}

/**
 * Immutable snapshot of the user's current History filter selection.
 *
 * Modelled as a single value so it can flow as one [StateFlow] emission
 * (rather than orchestrating five separate flows in the ViewModel) and be
 * persisted atomically through `UiPreferencesRepository`. Default values
 * deliberately match an "everything" filter so the History screen renders
 * sensibly on first launch when no preference has been stored yet.
 *
 * Date bounds use epoch milliseconds (UTC) — the History screen converts
 * them to `LocalDate` for display via the user's system zone, while the
 * repository's SQL query treats them as raw epoch values to keep the
 * predicate index-friendly.
 */
data class HistoryFilter(
    val query: String = "",
    val minSeverity: Int = LogValidator.MIN_SEVERITY,
    val maxSeverity: Int = LogValidator.MAX_SEVERITY,
    val startAfterEpochMillis: Long? = null,
    val startBeforeEpochMillis: Long? = null,
    val tags: Set<String> = emptySet(),
    val sort: HistorySort = HistorySort.Default,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val hasSeverityBound: Boolean
        get() = minSeverity > LogValidator.MIN_SEVERITY || maxSeverity < LogValidator.MAX_SEVERITY
    val hasDateBound: Boolean
        get() = startAfterEpochMillis != null || startBeforeEpochMillis != null
    val hasTagFilter: Boolean get() = tags.isNotEmpty()
    val hasActiveFilters: Boolean
        get() = hasQuery || hasSeverityBound || hasDateBound || hasTagFilter

    companion object {
        val Default: HistoryFilter = HistoryFilter()
    }
}

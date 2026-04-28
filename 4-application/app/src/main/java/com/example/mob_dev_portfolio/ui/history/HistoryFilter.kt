package com.example.mob_dev_portfolio.ui.history

import com.example.mob_dev_portfolio.ui.log.LogValidator

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

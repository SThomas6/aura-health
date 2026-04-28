package com.example.mob_dev_portfolio.data.ai

/**
 * Coarse two-bucket clinical guidance used for the *notification headline*.
 *
 * The notification is glanceable — a user looking at their lock screen
 * shouldn't have to parse a medical paragraph to know whether they need to
 * read the full result now. [Clear] and [SeekAdvice] are the only buckets
 * the story's acceptance criterion actually uses ("in the clear" vs "seek
 * medical advice"), so we deliberately keep the enum small.
 *
 * Guidance is NOT a diagnosis — the body text always points the user back
 * to the full in-app summary for context.
 */
enum class AnalysisGuidance(val headline: String, val bodyHint: String) {
    Clear(
        headline = "You look in the clear",
        bodyHint = "Nothing urgent stood out in your recent logs. Tap to read the full summary.",
    ),
    SeekAdvice(
        headline = "Consider seeking medical advice",
        bodyHint = "Your AI analysis flagged patterns worth reviewing with a clinician. Tap to read it.",
    ),
    ;

    companion object {
        /**
         * Derive a bucket from the AI summary.
         *
         * Primary signal: we ask the model (see `GeminiClient.buildPrompt`)
         * to emit a line containing `GUIDANCE: clear` or
         * `GUIDANCE: seek medical advice`. When the model obeys we lift the
         * bucket straight from that marker.
         *
         * Fallback: the model sometimes paraphrases. To keep the
         * notification specific we fall back to a local severity check —
         * if any recent log has severity ≥ [SEVERITY_WATERSHED] (the same
         * "red band" the Log screen uses) we bias to [SeekAdvice]. That way
         * the notification never blandly says "in the clear" while the
         * user's own data shows an 8/10 migraine this week.
         */
        fun fromSummary(summary: String, maxSeverityInRecentLogs: Int): AnalysisGuidance {
            val marker = MARKER_REGEX.find(summary)?.groupValues?.getOrNull(1)
                ?.lowercase()
                ?.trim()
            if (marker != null) {
                when {
                    marker.contains("seek") ||
                        marker.contains("advice") ||
                        marker.contains("medical") ||
                        marker.contains("urgent") -> return SeekAdvice
                    marker.contains("clear") ||
                        marker.contains("fine") ||
                        marker.contains("no concern") ||
                        marker.contains("no-concern") -> return Clear
                }
            }
            return if (maxSeverityInRecentLogs >= SEVERITY_WATERSHED) SeekAdvice else Clear
        }

        /**
         * Matches `GUIDANCE:` or `Guidance -` followed by a short phrase.
         * Multiline-dotall deliberately off — the marker must fit on one
         * line, otherwise we'd slurp the entire summary as the "bucket".
         */
        private val MARKER_REGEX = Regex(
            """(?i)guidance\s*[:\-]\s*([A-Za-z][A-Za-z \-]{0,40})""",
        )

        /**
         * Severity 7-10 is the "red band" the Log screen renders in warning
         * colour; mirroring that threshold here keeps the notification's
         * copy aligned with what the user has already seen in-app.
         */
        private const val SEVERITY_WATERSHED: Int = 7
    }
}

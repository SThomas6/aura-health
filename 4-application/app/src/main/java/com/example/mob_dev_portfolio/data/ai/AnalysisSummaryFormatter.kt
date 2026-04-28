package com.example.mob_dev_portfolio.data.ai

/**
 * Shared helpers for massaging raw Gemini summary text before it reaches
 * a renderer.
 *
 * The model is instructed (see [HttpGeminiClient.buildPrompt]) to emit
 * internal bookkeeping markers on their own lines — `GUIDANCE: ...` at
 * the top and `NHS_REFERENCE: ...` at the bottom. Those markers are
 * useful for machine classification (bucket derivation, always-visible
 * disclaimer) but they should NOT appear in the user-visible markdown,
 * because:
 *
 *  - the GUIDANCE bucket is already shown as a pill / headline, so the
 *    literal `GUIDANCE: seek medical advice` line is redundant;
 *  - the NHS_REFERENCE line is redisplayed as a dedicated disclaimer
 *    card (in-app) / footer line (PDF), so rendering it inline would
 *    double-up and look odd.
 *
 * Keeping the stripping logic in one place means the markdown renderer,
 * the analysis detail screen, and the PDF generator all agree on what
 * the "clean" summary looks like.
 */
object AnalysisSummaryFormatter {

    /** Marker the prompt asks the model to emit as the last line. */
    private const val NHS_REFERENCE_URL: String = "https://www.nhs.uk"

    private val GUIDANCE_LINE = Regex("""(?i)^\s*guidance\s*[:\-].*$""")
    private val NHS_LINE = Regex("""(?i)^\s*nhs_reference\s*[:\-].*$""")

    /**
     * Strip the `GUIDANCE:` and `NHS_REFERENCE:` bookkeeping lines from
     * [source] and collapse the resulting blank gaps. Safe on summaries
     * that don't contain either marker — returns them unchanged (modulo
     * whitespace trimming).
     */
    fun stripInternalMarkers(source: String): String {
        if (source.isBlank()) return source
        val out = source
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split('\n')
            .asSequence()
            .filterNot { line ->
                GUIDANCE_LINE.matches(line) || NHS_LINE.matches(line)
            }
            .joinToString("\n")
            .trim()
        // Collapse 3+ consecutive blank lines into a single blank line.
        return out.replace(Regex("""\n{3,}"""), "\n\n")
    }

    /**
     * Returns the NHS reference sentence the prompt asks the model to
     * emit verbatim — used as a fallback when we want to render the
     * disclaimer ourselves, regardless of whether the model complied.
     */
    const val NHS_DISCLAIMER: String =
        "This is not a medical diagnosis. For full symptom information on any condition mentioned above, check the NHS website at $NHS_REFERENCE_URL."

    // Urgency keywords the prompt asks the model to use when the pattern
    // warrants same-day / emergency care. We don't try to be clever here —
    // a literal match on the terms we instruct the model to produce is
    // more robust than trying to infer urgency from arbitrary prose.
    private val URGENT_HINTS: List<Regex> = listOf(
        Regex("""(?i)\b999\b"""),
        Regex("""(?i)\bA&E\b"""),
        Regex("""(?i)\bemergency\b"""),
        Regex("""(?i)\blife[- ]threatening\b"""),
        Regex("""(?i)\bimmediate(?:ly)?\b\s+(?:medical|care|attention)"""),
    )

    /**
     * Three-tier severity classification for the analysis hero card.
     *
     * The user asked for a red / amber / green band on top of the
     * Gemini summary reflecting how concerning the result is. We only
     * persist a two-bucket [AnalysisGuidance] (Clear / SeekAdvice), so
     * the middle tier is derived by looking for same-day-care keywords
     * the prompt instructs the model to use when patterns meet the
     * life-threatening screening criteria. In order of severity:
     *
     *   - `Urgent` (red): SeekAdvice AND the summary mentions 999 /
     *     A&E / emergency / life-threatening / "immediate medical".
     *   - `Watch`  (amber): SeekAdvice without any urgent keywords —
     *     typically a "speak to your GP" recommendation.
     *   - `AllClear` (green): the guidance bucket was Clear.
     *
     * Keeping the classifier here rather than in the UI means the hero
     * card, the list row, and any future notification copy all agree on
     * the same tiers from the same input.
     */
    enum class SeverityTier { AllClear, Watch, Urgent }

    fun severityTier(guidance: AnalysisGuidance, summary: String): SeverityTier {
        if (guidance == AnalysisGuidance.Clear) return SeverityTier.AllClear
        val looksUrgent = URGENT_HINTS.any { it.containsMatchIn(summary) }
        return if (looksUrgent) SeverityTier.Urgent else SeverityTier.Watch
    }
}

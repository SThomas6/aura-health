package com.example.mob_dev_portfolio.data.report

import androidx.core.graphics.toColorInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure-text helpers extracted from [HealthReportPdfGenerator] so the
 * generator file can stay focused on canvas / page-break logic.
 *
 * Everything in this file is deterministic and has no Android Graphics
 * dependency beyond the colour-int return on [severityFillColor]. Each
 * function is unit-testable on the JVM without standing up Skia.
 *
 * The "why" for the sanitisation lives on [sanitizeForPdf]; the short
 * version is that Skia's text layout can SIGSEGV on certain combinations
 * of UTF-16 surrogate halves, control codepoints, and pathological
 * unbreakable character runs. We scrub here before any user / AI text
 * reaches `Paint.drawText` / `StaticLayout`.
 */

/** Soft-break threshold for unbreakable character runs. */
private const val MAX_UNBREAKABLE_RUN = 80

/**
 * Pick the soft severity-band fill colour used in the report's
 * symptom rows. Returns an `android.graphics.Paint`-compatible RGB
 * int (not a Compose `Color`) because the caller works in the
 * classic colour space.
 */
internal fun severityFillColor(severity: Int): Int {
    val s = severity.coerceIn(1, 10)
    return when {
        s <= 3 -> "#D7F4E4".toColorInt()
        s <= 6 -> "#FFE8BF".toColorInt()
        else -> "#FADAD1".toColorInt()
    }
}

/**
 * Defensive text sanitizer for anything that reaches Paint / Skia.
 *
 * There's a well-known class of SIGSEGV crashes on Android's text
 * layout pipeline (libminikin / libhwui) triggered by:
 *
 *   - Unpaired UTF-16 surrogate halves (invalid unicode),
 *   - C0/C1 control characters other than `\n` and `\t`,
 *   - Zero-width / bidi control codepoints in long runs,
 *   - Absurdly long unbreakable word runs that overflow the
 *     native line-breaker's internal buffers.
 *
 * User-supplied fields (symptom description, notes) and raw AI
 * output can all contain any of those. We scrub here — cheap, local,
 * no dependency on the font fallback chain — rather than trying to
 * guess which specific char pattern will crash a given device.
 *
 * Sanitisation is intentionally destructive: we're rendering a PDF,
 * not preserving the user's exact keystrokes. The in-app UI shows
 * the raw text; this only runs on the way into Skia.
 */
internal fun sanitizeForPdf(source: String): String {
    if (source.isEmpty()) return source
    val sb = StringBuilder(source.length)
    var i = 0
    var runLength = 0
    while (i < source.length) {
        val ch = source[i]
        val code = ch.code
        val keep: Char = when {
            // Tab / newline survive.
            ch == '\n' || ch == '\t' -> ch
            // C0 / DEL control range — replace with space.
            code < 0x20 || code == 0x7F -> ' '
            // C1 control range — replace with space.
            code in 0x80..0x9F -> ' '
            // Bidi + zero-width + formatting controls that minikin
            // reportedly trips on in long runs.
            code == 0x200B || code == 0x200C || code == 0x200D ||
                code == 0xFEFF || code in 0x202A..0x202E ||
                code in 0x2066..0x2069 -> ' '
            // Unpaired high surrogate — must be followed by a low
            // surrogate. Peek ahead; drop if not paired.
            ch.isHighSurrogate() -> {
                val next = if (i + 1 < source.length) source[i + 1] else null
                if (next != null && next.isLowSurrogate()) {
                    sb.append(ch)
                    sb.append(next)
                    i += 2
                    runLength += 1
                    continue
                } else {
                    ' '
                }
            }
            // Unpaired low surrogate — drop.
            ch.isLowSurrogate() -> ' '
            else -> ch
        }
        if (keep == '\n' || keep == ' ' || keep == '\t') {
            runLength = 0
        } else {
            runLength += 1
        }
        // Break pathologically long unbreakable runs by inserting a
        // soft space. 80 chars is well above any natural word length
        // but well under what would wrap.
        if (runLength > MAX_UNBREAKABLE_RUN) {
            sb.append(' ')
            runLength = 0
        }
        sb.append(keep)
        i += 1
    }
    return sb.toString()
}

/**
 * Strip the deliberately-small subset of Markdown the AI prompt
 * permits (`##`/`###` headings, `- ` bullets, `**bold**` /
 * `*italic*` / `_italic_` / `` `code` ``) into plain lines suitable
 * for the PDF's flowing-text rendering. Leaves the visible text
 * intact — only formatting markers are stripped.
 */
internal fun flattenMarkdown(source: String): String {
    if (source.isBlank()) return source
    val normalised = source.replace("\r\n", "\n").replace("\r", "\n")
    val sb = StringBuilder()
    normalised.split('\n').forEach { rawLine ->
        val line = rawLine.trimEnd()
        val emitted = when {
            line.isBlank() -> ""
            line.startsWith("### ") -> line.removePrefix("### ")
            line.startsWith("## ") -> line.removePrefix("## ")
            line.startsWith("# ") -> line.removePrefix("# ")
            line.trimStart().startsWith("- ") ||
                line.trimStart().startsWith("* ") ->
                "• " + line.trimStart().drop(2)
            else -> line
        }
        val stripped = emitted
            .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""__(.+?)__"""), "$1")
            .replace(Regex("""(?<![\\*])\*(?!\s)([^*\n]+?)\*"""), "$1")
            .replace(Regex("""(?<![\\_])_(?!\s)([^_\n]+?)_"""), "$1")
            .replace(Regex("""`([^`]+?)`"""), "$1")
        if (stripped.isNotEmpty()) sb.appendLine(stripped)
    }
    return sb.toString().trimEnd()
}

/**
 * Format an epoch-millis instant for the report's row timestamps.
 *
 * Locale is pinned to UK (rather than `getDefault()`) for two reasons:
 *  1. The PDF is the doctor-handover artefact. Doctors want stable
 *     English month names ("26 Apr 2026"), not whatever locale the
 *     patient's phone happens to be set to.
 *  2. `Locale.getDefault()` can pull in non-Latin month names
 *     (Cyrillic, Arabic, Han) that have triggered libminikin SIGSEGVs
 *     on the bitmap text path in the past. UK month names stay in
 *     the ASCII Latin block.
 *
 * Built per-call to avoid the "Constant Locale" lint warning and to
 * cost nothing on the cold-start path.
 */
internal fun formatInstantForReport(epochMillis: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.UK)
    return dateTime.format(fmt)
}

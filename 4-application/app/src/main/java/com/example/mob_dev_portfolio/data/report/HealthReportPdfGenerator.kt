package com.example.mob_dev_portfolio.data.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Draws a [ReportSnapshot] onto a multi-page A4 PDF using Android's
 * native [PdfDocument] + [Canvas] — no third-party library, no network,
 * entirely offline.
 *
 * The layout walks a single "ink cursor" ([currentY]) down the page.
 * Any draw call that would overflow the printable area commits the
 * current page, opens a fresh one, and re-seats the cursor at the top
 * margin. Section headers resume on the new page for continuity.
 *
 * Why we roll our own instead of pulling something richer (e.g. PdfBox,
 * OpenPDF):
 *  - The story mandates native `PdfDocument` + `Canvas`.
 *  - Licensing is clean: framework API, no attribution.
 *  - APK stays lean — no extra dependency.
 *  - Everything below runs offline.
 */
class HealthReportPdfGenerator(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Write [snapshot] to a fresh PDF in the app's cache dir and return
     * the [File]. Overwrites any previous report — we only ever keep
     * one cached report at a time to avoid orphaning files the user can
     * no longer reach.
     */
    fun writeToCache(snapshot: ReportSnapshot, fileName: String = DEFAULT_FILE_NAME): File {
        val outputDir = File(context.cacheDir, REPORTS_SUBDIR).apply { mkdirs() }
        val file = File(outputDir, fileName)
        if (file.exists()) file.delete()
        val document = PdfDocument()
        try {
            val renderer = PageRenderer(document)
            renderer.render(snapshot)
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    private inner class PageRenderer(private val document: PdfDocument) {
        // A4 at 72dpi → 595 x 842pt, which is what PdfDocument uses as
        // its coordinate space. Material for Print-style margins.
        private val pageWidth = 595
        private val pageHeight = 842
        private val marginX = 44f
        private val marginY = 56f
        private val printableWidth: Float get() = pageWidth - marginX * 2

        private var pageIndex = 0
        private var currentPage: PdfDocument.Page? = null
        private var currentCanvas: Canvas? = null
        private var currentY: Float = marginY

        fun render(snapshot: ReportSnapshot) {
            openPage()
            drawHeader(snapshot)
            drawSectionTitle("Symptom logs")
            if (snapshot.logs.isEmpty()) {
                drawMuted("No symptom logs have been recorded yet.")
            } else {
                snapshot.logs.forEach { drawLogEntry(it) }
            }
            drawSpacer(14f)
            drawSectionTitle("AI analysis insights")
            if (snapshot.analyses.isEmpty()) {
                drawMuted("No AI analyses have been run yet.")
            } else {
                snapshot.analyses.forEach { drawAnalysisEntry(it) }
            }
            drawSpacer(18f)
            drawSummary(snapshot)
            closePage()
        }

        private fun openPage() {
            pageIndex += 1
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
            val page = document.startPage(info)
            currentPage = page
            currentCanvas = page.canvas
            currentY = marginY
            drawRunningFooter()
        }

        private fun closePage() {
            currentPage?.let { document.finishPage(it) }
            currentPage = null
            currentCanvas = null
        }

        /**
         * Guarantees at least [requiredHeight] of ink-room on the
         * current page — otherwise starts a new page. All draw helpers
         * call this before touching the canvas.
         */
        private fun ensureSpace(requiredHeight: Float) {
            val limit = pageHeight - marginY
            if (currentY + requiredHeight > limit) {
                closePage()
                openPage()
            }
        }

        private fun drawRunningFooter() {
            val canvas = currentCanvas ?: return
            val paint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 8.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val y = pageHeight - marginY + 22f
            canvas.drawText(
                "Aura Health — personal health report · page $pageIndex",
                marginX,
                y,
                paint,
            )
        }

        private fun drawHeader(snapshot: ReportSnapshot) {
            val canvas = currentCanvas ?: return
            // Brand bar across the top.
            val titlePaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val subtitlePaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 10.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            ensureSpace(58f)
            canvas.drawText("Aura Health Report", marginX, currentY + 18f, titlePaint)
            val generated = formatInstant(snapshot.generatedAtEpochMillis)
            canvas.drawText(
                "Generated $generated · Chronological (oldest first)",
                marginX,
                currentY + 34f,
                subtitlePaint,
            )
            // Accent rule.
            val rulePaint = Paint().apply {
                color = COLOR_ACCENT
                strokeWidth = 1.5f
            }
            canvas.drawLine(
                marginX,
                currentY + 46f,
                pageWidth - marginX,
                currentY + 46f,
                rulePaint,
            )
            currentY += 58f
        }

        private fun drawSectionTitle(text: String) {
            val canvas = currentCanvas ?: return
            val paint = TextPaint().apply {
                color = COLOR_INK
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            ensureSpace(24f)
            canvas.drawText(text, marginX, currentY + 14f, paint)
            val rulePaint = Paint().apply {
                color = COLOR_RULE
                strokeWidth = 0.8f
            }
            canvas.drawLine(
                marginX,
                currentY + 20f,
                pageWidth - marginX,
                currentY + 20f,
                rulePaint,
            )
            currentY += 26f
        }

        private fun drawMuted(text: String) {
            val paint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 10.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            drawWrappedText(text, paint)
            currentY += 6f
        }

        private fun drawSpacer(height: Float) {
            // Page break awareness: even a spacer can flip the page if
            // we're right on the margin.
            ensureSpace(height)
            currentY += height
        }

        private fun drawLogEntry(log: ReportLog) {
            // Estimate block height so the whole entry stays together
            // where possible (at minimum the title + date stay on the
            // same page as the severity pill).
            ensureSpace(72f)
            val canvas = currentCanvas ?: return

            // Title + severity pill row.
            val titlePaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText(
                log.symptomName.ifBlank { "Untitled symptom" },
                marginX,
                currentY + 12f,
                titlePaint,
            )
            drawSeverityPill(canvas, log.severity, topY = currentY)
            currentY += 18f

            // Meta line (date · duration · location · weather)
            val metaPaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 9.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val metaPieces = mutableListOf<String>()
            metaPieces += "Started ${formatInstant(log.startEpochMillis)}"
            log.endEpochMillis?.let { metaPieces += "Ended ${formatInstant(it)}" }
            if (!log.locationName.isNullOrBlank()) metaPieces += log.locationName
            if (!log.weatherDescription.isNullOrBlank()) {
                val temp = log.temperatureCelsius?.let { " (${"%.1f".format(it)}°C)" } ?: ""
                metaPieces += "${log.weatherDescription}$temp"
            }
            drawWrappedText(metaPieces.joinToString("  ·  "), metaPaint)

            // Optional long-form text fields.
            val bodyPaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 10.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            if (log.description.isNotBlank()) {
                drawSpacer(3f)
                drawWrappedText(log.description, bodyPaint)
            }
            if (log.notes.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Notes", log.notes)
            }
            if (log.medication.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Medication", log.medication)
            }
            if (log.contextTags.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Context", log.contextTags.replace("|", ", "))
            }
            // Subtle rule between entries.
            drawSpacer(8f)
            val rulePaint = Paint().apply {
                color = COLOR_RULE_SOFT
                strokeWidth = 0.5f
            }
            canvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, rulePaint)
            currentY += 10f
        }

        private fun drawAnalysisEntry(analysis: ReportAnalysis) {
            ensureSpace(56f)
            val canvas = currentCanvas ?: return
            val titlePaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText(analysis.headline, marginX, currentY + 12f, titlePaint)
            drawGuidancePill(canvas, analysis.guidance, topY = currentY)
            currentY += 18f

            val metaPaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 9.5f
                isAntiAlias = true
            }
            canvas.drawText(
                "Completed ${formatInstant(analysis.completedAtEpochMillis)}",
                marginX,
                currentY + 10f,
                metaPaint,
            )
            currentY += 14f

            val bodyPaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 10.5f
                isAntiAlias = true
            }
            // Flatten markdown aggressively — headings lose their `#`,
            // bullets keep a bullet glyph, and emphasis markers are
            // stripped. We can't render bold in a StaticLayout span
            // without more plumbing, and the user can go back to the
            // in-app detail view for the rich version.
            drawWrappedText(flattenMarkdown(analysis.summaryText), bodyPaint)

            drawSpacer(8f)
            val rulePaint = Paint().apply {
                color = COLOR_RULE_SOFT
                strokeWidth = 0.5f
            }
            canvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, rulePaint)
            currentY += 10f
        }

        private fun drawLabelledParagraph(label: String, body: String) {
            val labelPaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val bodyPaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 10.5f
                isAntiAlias = true
            }
            ensureSpace(16f)
            currentCanvas?.drawText(label, marginX, currentY + 10f, labelPaint)
            currentY += 14f
            drawWrappedText(body, bodyPaint)
        }

        private fun drawSeverityPill(canvas: Canvas, severity: Int, topY: Float) {
            val label = "Severity $severity/10"
            val pillPaint = Paint().apply {
                color = severityFillColor(severity)
                isAntiAlias = true
            }
            val textPaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val textWidth = textPaint.measureText(label)
            val pillWidth = textWidth + 18f
            val pillHeight = 16f
            val x = pageWidth - marginX - pillWidth
            val rect = RectF(x, topY, x + pillWidth, topY + pillHeight)
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, pillPaint)
            canvas.drawText(label, x + 9f, topY + 11f, textPaint)
        }

        private fun drawGuidancePill(
            canvas: Canvas,
            guidance: com.example.mob_dev_portfolio.data.ai.AnalysisGuidance,
            topY: Float,
        ) {
            val (fill, label) = when (guidance) {
                com.example.mob_dev_portfolio.data.ai.AnalysisGuidance.Clear ->
                    Pair(COLOR_PILL_CLEAR, "CLEAR")
                com.example.mob_dev_portfolio.data.ai.AnalysisGuidance.SeekAdvice ->
                    Pair(COLOR_PILL_SEEK, "SEEK ADVICE")
            }
            val textPaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val textWidth = textPaint.measureText(label)
            val pillWidth = textWidth + 18f
            val pillHeight = 16f
            val x = pageWidth - marginX - pillWidth
            val rect = RectF(x, topY, x + pillWidth, topY + pillHeight)
            val pillPaint = Paint().apply {
                color = fill
                isAntiAlias = true
            }
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, pillPaint)
            canvas.drawText(label, x + 9f, topY + 11f, textPaint)
        }

        private fun drawSummary(snapshot: ReportSnapshot) {
            ensureSpace(100f)
            val canvas = currentCanvas ?: return

            // Card background so the summary reads as the document's
            // conclusion, not just another paragraph.
            val cardPaint = Paint().apply {
                color = COLOR_SURFACE
                isAntiAlias = true
            }
            val rect = RectF(
                marginX,
                currentY,
                pageWidth - marginX,
                currentY + 96f,
            )
            canvas.drawRoundRect(rect, 10f, 10f, cardPaint)

            val titlePaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("Summary", marginX + 14f, currentY + 20f, titlePaint)

            val labelPaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 9.5f
                isAntiAlias = true
            }
            val valuePaint = TextPaint().apply {
                color = COLOR_INK
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val columnWidth = (printableWidth - 28f) / 2f
            val leftX = marginX + 14f
            val rightX = leftX + columnWidth

            canvas.drawText("Total symptom entries", leftX, currentY + 40f, labelPaint)
            canvas.drawText(snapshot.totalLogCount.toString(), leftX, currentY + 66f, valuePaint)

            canvas.drawText("Average severity", rightX, currentY + 40f, labelPaint)
            val avg = snapshot.averageSeverity
            val avgLabel = if (avg == null) {
                "—"
            } else {
                "${"%.2f".format(avg)} / 10"
            }
            canvas.drawText(avgLabel, rightX, currentY + 66f, valuePaint)

            val helperPaint = TextPaint().apply {
                color = COLOR_MUTED
                textSize = 8.5f
                isAntiAlias = true
            }
            canvas.drawText(
                "Computed directly from the symptom_logs table (COUNT + AVG).",
                leftX,
                currentY + 86f,
                helperPaint,
            )

            currentY += 104f
        }

        /**
         * Core text-drawing primitive — wraps [text] at [printableWidth]
         * using [StaticLayout] and advances [currentY] accordingly.
         * Handles page-break mid-paragraph by re-laying-out the
         * remainder on a fresh page.
         */
        private fun drawWrappedText(text: String, paint: TextPaint) {
            var remaining = text
            while (remaining.isNotEmpty()) {
                val canvas = currentCanvas ?: return
                val availableHeight = (pageHeight - marginY) - currentY
                if (availableHeight < paint.textSize * 1.4f) {
                    closePage()
                    openPage()
                    continue
                }
                val layout = StaticLayout.Builder.obtain(
                    remaining,
                    0,
                    remaining.length,
                    paint,
                    printableWidth.roundToInt(),
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1f)
                    .setIncludePad(false)
                    .build()

                val fullHeight = layout.height.toFloat()
                if (fullHeight <= availableHeight) {
                    canvas.save()
                    canvas.translate(marginX, currentY)
                    layout.draw(canvas)
                    canvas.restore()
                    currentY += fullHeight + 2f
                    return
                }

                // Fit as many lines as we can, then continue the
                // remainder on the next page.
                var fittingLines = layout.lineCount
                while (fittingLines > 0 &&
                    layout.getLineBottom(fittingLines - 1) > availableHeight
                ) {
                    fittingLines -= 1
                }
                if (fittingLines == 0) {
                    closePage()
                    openPage()
                    continue
                }
                val splitOffset = layout.getLineEnd(fittingLines - 1)
                val chunk = remaining.substring(0, splitOffset)
                val chunkLayout = StaticLayout.Builder.obtain(
                    chunk,
                    0,
                    chunk.length,
                    paint,
                    printableWidth.roundToInt(),
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1f)
                    .setIncludePad(false)
                    .build()
                canvas.save()
                canvas.translate(marginX, currentY)
                chunkLayout.draw(canvas)
                canvas.restore()
                currentY += chunkLayout.height.toFloat() + 2f
                remaining = remaining.substring(splitOffset)
                if (remaining.isNotEmpty()) {
                    closePage()
                    openPage()
                }
            }
        }
    }

    private fun severityFillColor(severity: Int): Int {
        // Soft mint → warm coral gradient matching the app's severity
        // band. These are RGB ints, not Compose Colors, because we're
        // talking to `android.graphics.Paint`.
        val s = severity.coerceIn(1, 10)
        return when {
            s <= 3 -> Color.parseColor("#D7F4E4")
            s <= 6 -> Color.parseColor("#FFE8BF")
            else -> Color.parseColor("#FADAD1")
        }
    }

    private fun flattenMarkdown(source: String): String {
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

    private fun formatInstant(epochMillis: Long): String {
        val dateTime = Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        return dateTime.format(DATE_FMT)
    }

    companion object {
        const val REPORTS_SUBDIR = "reports"
        const val DEFAULT_FILE_NAME = "aura_health_report.pdf"

        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.getDefault())

        // Paint colours — plain RGB ints (not Compose Colors) since
        // android.graphics.Paint works in the classic colour space.
        private val COLOR_INK: Int = Color.parseColor("#15201B")
        private val COLOR_MUTED: Int = Color.parseColor("#6A7671")
        private val COLOR_ACCENT: Int = Color.parseColor("#3EA887")
        private val COLOR_RULE: Int = Color.parseColor("#D6E2DC")
        private val COLOR_RULE_SOFT: Int = Color.parseColor("#EAEFEC")
        private val COLOR_SURFACE: Int = Color.parseColor("#F1F7F3")
        private val COLOR_PILL_CLEAR: Int = Color.parseColor("#D7F4E4")
        private val COLOR_PILL_SEEK: Int = Color.parseColor("#FADAD1")
    }
}

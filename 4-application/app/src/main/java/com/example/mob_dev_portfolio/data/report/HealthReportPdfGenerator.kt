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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Draws a [ReportSnapshot] onto a multi-page A4 PDF using Android's
 * native [PdfDocument] + [Canvas] — no third-party library, no network,
 * entirely offline.
 *
 * ### Storage model
 * The canonical on-disk artifact is **GZIP-compressed** (DEFLATE with
 * [Deflater.BEST_COMPRESSION]) and lives at
 * `cacheDir/reports/aura_health_report.pdf.gz`. Inspecting the device's
 * internal storage therefore shows the PDF in a compressed format, as
 * required by the user story's storage-efficiency acceptance criterion.
 *
 * Because `PdfRenderer` and external apps (email clients, PDF viewers)
 * cannot consume a gzipped stream directly, [writeToCache] also
 * materialises a short-lived uncompressed copy under `reports/preview/`
 * which the in-app preview and the share intent consume. That preview
 * directory is wiped at the start of every generation and cleared
 * explicitly by [clearTransientArtifacts], so the persistent footprint
 * on disk remains the compressed file alone.
 *
 * ### Drawing strategy
 * Paint objects are allocated once per [PageRenderer] and reused across
 * every draw call. Previously each helper newed up its own `TextPaint`,
 * which was cheap but wasteful — on a 30-entry report we were spawning
 * hundreds of paints.
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
     * Render [snapshot] to a compressed on-disk artifact plus a
     * transient uncompressed copy for preview/sharing.
     *
     * The PDF is rendered once into an in-memory buffer, then fanned
     * out to two destinations:
     *
     *  1. `reports/aura_health_report.pdf.gz` — GZIP DEFLATE at
     *     [Deflater.BEST_COMPRESSION]. This is the persistent "stored"
     *     form.
     *  2. `reports/preview/aura_health_report.pdf` — raw PDF bytes.
     *     Short-lived; cleared on the next generation.
     *
     * Both writes use a [BufferedOutputStream] to batch syscalls.
     */
    fun writeToCache(
        snapshot: ReportSnapshot,
        fileName: String? = null,
    ): ReportArtifacts {
        val outputDir = File(context.cacheDir, REPORTS_SUBDIR).apply { mkdirs() }
        val previewDir = File(outputDir, PREVIEW_SUBDIR).apply { mkdirs() }

        // Clear only the *transient* preview directory. The compressed
        // archives under `reports/` are the user-visible history (managed
        // by the ReportArchiveDao) — wiping them would delete that
        // history on every generation, which is exactly what the PDF
        // Report History & File Management story forbids.
        previewDir.listFiles()?.forEach { it.delete() }

        val generatedAtEpochMillis = clock()
        // Per-generation unique names keep the history list honest and
        // let the unique index on `compressedFileName` guard against the
        // (vanishingly small) chance of a double-insert. Millisecond
        // precision is more than enough — users can't press Generate
        // faster than that.
        val baseName = fileName ?: "aura_health_report_$generatedAtEpochMillis.pdf"
        val compressedFile = File(outputDir, "$baseName$COMPRESSED_SUFFIX")
        val previewFile = File(previewDir, baseName)

        // 1. Render to an in-memory buffer so we can write to both
        //    destinations without re-running the Canvas pipeline.
        //    Note: we write DIRECTLY to the ByteArrayOutputStream — not
        //    through a BufferedOutputStream — because (a) BAOS is
        //    already in-memory so buffering adds nothing, and more
        //    importantly (b) a BufferedOutputStream that isn't
        //    explicitly flushed/closed leaves its last chunk in the
        //    buffer, which produces a truncated, unparseable PDF. That
        //    corruption would crash PdfRenderer when the preview loads.
        val pdfBytes = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE).use { mem ->
            val document = PdfDocument()
            try {
                PageRenderer(document).render(snapshot)
                document.writeTo(mem)
            } finally {
                document.close()
            }
            mem.toByteArray()
        }

        // 2. Persist canonical compressed copy. GZIPOutputStream defaults
        //    to moderate compression; bumping to BEST_COMPRESSION shaves
        //    a further 5-15% at the cost of ~2x compression time — fine
        //    for a one-off user-triggered action.
        BufferedOutputStream(
            object : GZIPOutputStream(compressedFile.outputStream()) {
                init {
                    def.setLevel(Deflater.BEST_COMPRESSION)
                }
            },
        ).use { it.write(pdfBytes) }

        // 3. Materialise the uncompressed preview file. PdfRenderer needs
        //    a real PDF on disk, and the share intent needs one that
        //    external apps can actually open.
        BufferedOutputStream(previewFile.outputStream()).use { it.write(pdfBytes) }

        return ReportArtifacts(
            compressedFile = compressedFile,
            previewFile = previewFile,
            uncompressedBytes = pdfBytes.size.toLong(),
            compressedBytes = compressedFile.length(),
            generatedAtEpochMillis = generatedAtEpochMillis,
        )
    }

    /**
     * Re-materialise a preview PDF from the compressed artifact — used
     * if the transient copy has been cleared (e.g. cache eviction)
     * between generation and a subsequent share.
     */
    /**
     * Re-materialise a preview PDF from a specific compressed archive —
     * used by the history screen's Open/Share flows, and as a fallback
     * for the active generation if its transient copy was evicted.
     *
     * [compressedFileName] is the name stored in `ReportArchiveEntity`,
     * i.e. including the `.gz` suffix.
     */
    fun materialisePreview(compressedFileName: String): File? {
        val reportsDir = File(context.cacheDir, REPORTS_SUBDIR)
        val compressedFile = File(reportsDir, compressedFileName)
        if (!compressedFile.exists()) return null
        val previewDir = File(reportsDir, PREVIEW_SUBDIR).apply { mkdirs() }
        val previewName = compressedFileName.removeSuffix(COMPRESSED_SUFFIX)
        val previewFile = File(previewDir, previewName)
        BufferedInputStream(GZIPInputStream(compressedFile.inputStream())).use { input ->
            BufferedOutputStream(previewFile.outputStream()).use { output ->
                input.copyTo(output)
            }
        }
        return previewFile
    }

    /**
     * File-first half of the two-step delete contract: remove the
     * compressed archive from disk, plus any transient preview derived
     * from it. The caller deletes the Room row *after* this succeeds,
     * so we never leave a metadata entry pointing at a missing file.
     *
     * Returns true iff the compressed file no longer exists when this
     * method returns (either we deleted it, or it wasn't there).
     */
    fun deleteArchive(compressedFileName: String): Boolean {
        val reportsDir = File(context.cacheDir, REPORTS_SUBDIR)
        val compressedFile = File(reportsDir, compressedFileName)
        val previewDir = File(reportsDir, PREVIEW_SUBDIR)
        val previewFile = File(previewDir, compressedFileName.removeSuffix(COMPRESSED_SUFFIX))
        // Always attempt the preview first — it's the cheap one, and if
        // the compressed delete throws we still want the transient gone.
        runCatching { if (previewFile.exists()) previewFile.delete() }
        if (!compressedFile.exists()) return true
        return compressedFile.delete()
    }

    /**
     * Remove the transient preview directory so the on-disk footprint
     * collapses back to just the compressed artifact. Called when the
     * user leaves the report screen.
     */
    fun clearTransientArtifacts() {
        val previewDir = File(File(context.cacheDir, REPORTS_SUBDIR), PREVIEW_SUBDIR)
        previewDir.listFiles()?.forEach { it.delete() }
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

        // -------------------------------------------------------------
        // Reusable paint objects.
        // We used to allocate these per-draw-call, which meant a 30-log
        // report spawned hundreds of TextPaints. Hoisting to fields is
        // the "efficient drawing strategy" half of the user story — one
        // allocation per render, mutated in-place where needed.
        // -------------------------------------------------------------
        private val titlePaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val subtitlePaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 10.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val sectionTitlePaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val entryTitlePaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val labelPaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val metaPaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 9.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val mutedBodyPaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 10.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val bodyPaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 10.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val pillLabelPaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val summaryTitlePaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val summaryValuePaint = TextPaint().apply {
            color = COLOR_INK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        private val helperPaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 8.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val footerPaint = TextPaint().apply {
            color = COLOR_MUTED
            textSize = 8.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val fillPaint = Paint().apply { isAntiAlias = true }
        private val strokePaint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 1f
        }

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
            val y = pageHeight - marginY + 22f
            canvas.drawText(
                "Aura Health — personal health report · page $pageIndex",
                marginX,
                y,
                footerPaint,
            )
        }

        private fun drawHeader(snapshot: ReportSnapshot) {
            val canvas = currentCanvas ?: return
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
            strokePaint.color = COLOR_ACCENT
            strokePaint.strokeWidth = 1.5f
            canvas.drawLine(
                marginX,
                currentY + 46f,
                pageWidth - marginX,
                currentY + 46f,
                strokePaint,
            )
            currentY += 58f
        }

        private fun drawSectionTitle(text: String) {
            val canvas = currentCanvas ?: return
            ensureSpace(24f)
            canvas.drawText(text, marginX, currentY + 14f, sectionTitlePaint)
            strokePaint.color = COLOR_RULE
            strokePaint.strokeWidth = 0.8f
            canvas.drawLine(
                marginX,
                currentY + 20f,
                pageWidth - marginX,
                currentY + 20f,
                strokePaint,
            )
            currentY += 26f
        }

        private fun drawMuted(text: String) {
            drawWrappedText(text, mutedBodyPaint)
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

            canvas.drawText(
                log.symptomName.ifBlank { "Untitled symptom" },
                marginX,
                currentY + 12f,
                entryTitlePaint,
            )
            drawSeverityPill(canvas, log.severity, topY = currentY)
            currentY += 18f

            // Meta line (date · duration · location · weather)
            val metaPieces = mutableListOf<String>()
            metaPieces += "Started ${formatInstant(log.startEpochMillis)}"
            log.endEpochMillis?.let { metaPieces += "Ended ${formatInstant(it)}" }
            if (!log.locationName.isNullOrBlank()) metaPieces += log.locationName
            if (!log.weatherDescription.isNullOrBlank()) {
                val temp = log.temperatureCelsius?.let { " (${"%.1f".format(it)}°C)" } ?: ""
                metaPieces += "${log.weatherDescription}$temp"
            }
            drawWrappedText(metaPieces.joinToString("  ·  "), metaPaint)

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
            strokePaint.color = COLOR_RULE_SOFT
            strokePaint.strokeWidth = 0.5f
            canvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, strokePaint)
            currentY += 10f
        }

        private fun drawAnalysisEntry(analysis: ReportAnalysis) {
            ensureSpace(56f)
            val canvas = currentCanvas ?: return
            canvas.drawText(analysis.headline, marginX, currentY + 12f, entryTitlePaint)
            drawGuidancePill(canvas, analysis.guidance, topY = currentY)
            currentY += 18f

            canvas.drawText(
                "Completed ${formatInstant(analysis.completedAtEpochMillis)}",
                marginX,
                currentY + 10f,
                metaPaint,
            )
            currentY += 14f

            // Flatten markdown aggressively — headings lose their `#`,
            // bullets keep a bullet glyph, and emphasis markers are
            // stripped. We can't render bold in a StaticLayout span
            // without more plumbing, and the user can go back to the
            // in-app detail view for the rich version.
            drawWrappedText(flattenMarkdown(analysis.summaryText), bodyPaint)

            drawSpacer(8f)
            strokePaint.color = COLOR_RULE_SOFT
            strokePaint.strokeWidth = 0.5f
            canvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, strokePaint)
            currentY += 10f
        }

        private fun drawLabelledParagraph(label: String, body: String) {
            ensureSpace(16f)
            currentCanvas?.drawText(label, marginX, currentY + 10f, labelPaint)
            currentY += 14f
            drawWrappedText(body, bodyPaint)
        }

        private fun drawSeverityPill(canvas: Canvas, severity: Int, topY: Float) {
            val label = "Severity $severity/10"
            val textWidth = pillLabelPaint.measureText(label)
            val pillWidth = textWidth + 18f
            val pillHeight = 16f
            val x = pageWidth - marginX - pillWidth
            val rect = RectF(x, topY, x + pillWidth, topY + pillHeight)
            fillPaint.color = severityFillColor(severity)
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fillPaint)
            canvas.drawText(label, x + 9f, topY + 11f, pillLabelPaint)
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
            val textWidth = pillLabelPaint.measureText(label)
            val pillWidth = textWidth + 18f
            val pillHeight = 16f
            val x = pageWidth - marginX - pillWidth
            val rect = RectF(x, topY, x + pillWidth, topY + pillHeight)
            fillPaint.color = fill
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fillPaint)
            canvas.drawText(label, x + 9f, topY + 11f, pillLabelPaint)
        }

        private fun drawSummary(snapshot: ReportSnapshot) {
            ensureSpace(100f)
            val canvas = currentCanvas ?: return

            // Card background so the summary reads as the document's
            // conclusion, not just another paragraph.
            fillPaint.color = COLOR_SURFACE
            val rect = RectF(
                marginX,
                currentY,
                pageWidth - marginX,
                currentY + 96f,
            )
            canvas.drawRoundRect(rect, 10f, 10f, fillPaint)

            canvas.drawText("Summary", marginX + 14f, currentY + 20f, summaryTitlePaint)

            val columnWidth = (printableWidth - 28f) / 2f
            val leftX = marginX + 14f
            val rightX = leftX + columnWidth

            canvas.drawText("Total symptom entries", leftX, currentY + 40f, metaPaint)
            canvas.drawText(
                snapshot.totalLogCount.toString(),
                leftX,
                currentY + 66f,
                summaryValuePaint,
            )

            canvas.drawText("Average severity", rightX, currentY + 40f, metaPaint)
            val avg = snapshot.averageSeverity
            val avgLabel = if (avg == null) {
                "—"
            } else {
                "${"%.2f".format(avg)} / 10"
            }
            canvas.drawText(avgLabel, rightX, currentY + 66f, summaryValuePaint)

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
        const val PREVIEW_SUBDIR = "preview"
        const val COMPRESSED_SUFFIX = ".gz"

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

/**
 * Bundle returned by [HealthReportPdfGenerator.writeToCache].
 *
 * [compressedFile] is the canonical on-disk artifact (GZIPed PDF);
 * [previewFile] is the short-lived uncompressed copy used by the
 * in-app preview and the share intent.
 */
data class ReportArtifacts(
    val compressedFile: File,
    val previewFile: File,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val generatedAtEpochMillis: Long,
) {
    /**
     * Fraction of the original size saved by compression in [0.0, 1.0].
     * A 40% ratio means the compressed file is 60% of the original.
     */
    val compressionRatio: Double
        get() = if (uncompressedBytes <= 0L) 0.0
        else 1.0 - (compressedBytes.toDouble() / uncompressedBytes.toDouble())
}

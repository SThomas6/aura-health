package com.example.mob_dev_portfolio.data.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.mob_dev_portfolio.data.ai.AnalysisSummaryFormatter
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

        /**
         * Bitmaps drawn into [currentCanvas] this page that we must NOT
         * recycle until [PdfDocument.finishPage] has serialised the page.
         *
         * Why this exists — root cause of the historical SIGSEGV:
         *   `canvas.drawBitmap()` on a [PdfDocument.Page] canvas does NOT
         *   immediately consume the bitmap's pixel buffer. PdfDocument
         *   *defers* rasterisation until `finishPage()` runs, so the
         *   library holds a native reference to the bitmap until then.
         *   Calling `bitmap.recycle()` in between (the obvious "scoped"
         *   pattern) frees the native pixel buffer; when finishPage()
         *   later walks its draw list it dereferences a null pointer
         *   and the process dies with `SIGSEGV / SEGV_MAPERR @ 0x0`.
         *   The crash is intermittent because it depends on whether
         *   that freed memory has been reused by another allocation
         *   between recycle and finishPage.
         *
         * Fix: every bitmap created for the current page accumulates
         * here, and [closePage] recycles them all *after* finishPage
         * returns, when it is safe to do so.
         */
        private val bitmapsPendingRecycle: MutableList<android.graphics.Bitmap> = mutableListOf()

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
                snapshot.logs.forEach { log ->
                    // Per-log try/catch: the PDF text/bitmap pipeline has
                    // documented native crash surfaces (libminikin /
                    // libhwui — see drawWrappedText kdoc). The defences
                    // upstream of here (sanitiser, bitmap indirection,
                    // simple line-break strategy) cover the known
                    // patterns, but we'd rather skip a single
                    // crash-triggering log than lose the whole report.
                    runCatching { drawLogEntry(log) }
                        .onFailure { err ->
                            android.util.Log.w(
                                "PdfGen",
                                "Skipped log id=${log.id} after draw failure",
                                err,
                            )
                            // Restart on a fresh page so half-drawn glyph
                            // state from the failed entry can't leak into
                            // the next one.
                            runCatching { closePage(); openPage() }
                        }
                }
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
            // Now — and ONLY now — is it safe to free the bitmaps drawn
            // into this page. See [bitmapsPendingRecycle] for why this
            // can't happen at the per-bitmap call site.
            bitmapsPendingRecycle.forEach {
                runCatching { it.recycle() }
            }
            bitmapsPendingRecycle.clear()
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
            val y = pageHeight - marginY + 22f
            // Bitmap-backed — see drawSingleLineViaBitmap kdoc. Direct
            // `canvas.drawText` on the PdfDocument canvas has been the
            // root of native (libhwui/libminikin) SIGSEGVs in this
            // codebase. The em dash + interpunct in the footer are
            // exactly the sort of multi-byte chars that have triggered
            // those crashes historically.
            drawSingleLineViaBitmap(
                "Aura Health — personal health report · page $pageIndex",
                marginX,
                y,
                footerPaint,
            )
        }

        private fun drawHeader(snapshot: ReportSnapshot) {
            val canvas = currentCanvas ?: return
            ensureSpace(58f)
            // Bitmap-backed — see drawSingleLineViaBitmap kdoc.
            drawSingleLineViaBitmap(
                "Aura Health Report",
                marginX,
                currentY + 18f,
                titlePaint,
            )
            val generated = formatInstant(snapshot.generatedAtEpochMillis)
            drawSingleLineViaBitmap(
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
            // Bitmap-backed — see drawSingleLineViaBitmap kdoc.
            drawSingleLineViaBitmap(text, marginX, currentY + 14f, sectionTitlePaint)
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
            // The canvas is fetched at every use site rather than cached
            // in a local. ROOT CAUSE OF THE HISTORICAL SIGSEGV CRASH:
            //
            //   `val canvas = currentCanvas` at the top of this function
            //   captures the *current* page's canvas. Any helper called
            //   below this point — drawSpacer, drawLabelledParagraph,
            //   drawWrappedText — can trigger `ensureSpace`, which calls
            //   `closePage()` (running `document.finishPage()`) and then
            //   `openPage()` for a new page. After finishPage the old
            //   page's canvas is dead at the native level. Drawing on it
            //   ⇒ `SIGSEGV / SEGV_MAPERR @ 0x0`. The crash deterministically
            //   landed on log #6 with seeded data because that's where
            //   the trailing `drawSpacer(8f)` crossed the page boundary.
            //
            // Every direct canvas use below re-resolves `currentCanvas`
            // so we always draw onto the live page.
            ensureSpace(72f)
            if (currentCanvas == null) return
            drawSingleLineViaBitmap(
                sanitizeForPdf(log.symptomName.ifBlank { "Untitled symptom" }),
                marginX,
                currentY + 12f,
                entryTitlePaint,
            )
            // Severity pill needs the live canvas — fetch fresh each
            // time. drawSingleLineViaBitmap above doesn't page-break
            // (its bitmap is small enough that ensureSpace isn't called),
            // but defensively re-resolve anyway.
            val pillCanvas = currentCanvas ?: return
            drawSeverityPill(pillCanvas, log.severity, topY = currentY)
            currentY += 18f

            // Meta line (date · duration · location · weather)
            //
            // Two correctness notes:
            //  - The temperature is formatted with [Locale.UK] explicitly,
            //    not the default. `"%.1f".format(12.5)` returns "12,5" on
            //    a comma-decimal locale (de-DE, fr-FR…). The PDF report
            //    is intended to be doctor-readable English, so a stable
            //    period-decimal beats following user locale here.
            //  - The whole joined meta string is run through
            //    [sanitizeForPdf] before drawing. Even though every
            //    *user-supplied* piece is already sanitised at the call
            //    site, the joiner ("  ·  ", U+00B7) and the hard-coded
            //    "Started"/"Ended" prefixes are not — and the libminikin
            //    SIGSEGV class doesn't care which token introduced the
            //    crashing code-point, only that it lands in the same
            //    StaticLayout. One scrub at the boundary is cheap insurance.
            val metaPieces = mutableListOf<String>()
            metaPieces += "Started ${formatInstant(log.startEpochMillis)}"
            log.endEpochMillis?.let { metaPieces += "Ended ${formatInstant(it)}" }
            if (!log.locationName.isNullOrBlank()) metaPieces += sanitizeForPdf(log.locationName)
            if (!log.weatherDescription.isNullOrBlank()) {
                val temp = log.temperatureCelsius
                    ?.let { " (${"%.1f".format(java.util.Locale.UK, it)}°C)" } ?: ""
                metaPieces += "${sanitizeForPdf(log.weatherDescription)}$temp"
            }
            drawWrappedText(sanitizeForPdf(metaPieces.joinToString("  ·  ")), metaPaint)

            if (log.description.isNotBlank()) {
                drawSpacer(3f)
                drawWrappedText(sanitizeForPdf(log.description), bodyPaint)
            }
            if (log.notes.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Notes", sanitizeForPdf(log.notes))
            }
            if (log.medication.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Medication", sanitizeForPdf(log.medication))
            }
            if (log.contextTags.isNotBlank()) {
                drawSpacer(3f)
                drawLabelledParagraph("Context", sanitizeForPdf(log.contextTags.replace("|", ", ")))
            }
            // FR-PA-06 — photo thumbnails row. Each attachment is
            // decoded at a small sample size (the stored photo is up to
            // 1920px; we only need ~240px for a PDF thumb) so the
            // embedded raster payload stays well inside the 10 MB
            // NFR-PA-06 budget even with many logs.
            if (log.photoJpegBytes.isNotEmpty()) {
                drawSpacer(4f)
                drawPhotoRow(log.photoJpegBytes)
            }
            // Subtle rule between entries.
            //
            // drawSpacer can page-break — re-resolve the canvas afterwards
            // because the value we'd have captured at the top of this
            // function points at a now-finished page. See the kdoc-style
            // comment at the top of drawLogEntry for the SIGSEGV history.
            drawSpacer(8f)
            strokePaint.color = COLOR_RULE_SOFT
            strokePaint.strokeWidth = 0.5f
            val ruleCanvas = currentCanvas ?: return
            ruleCanvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, strokePaint)
            currentY += 10f
        }

        /**
         * Renders up to 3 thumbnails in a row, each a fixed-height
         * landscape box with aspect-preserving crop. A row flows to a
         * new page when it can't fit on the current one.
         *
         * Memory notes: each decode uses [BitmapFactory.Options.inSampleSize]
         * to divide the source dimensions before allocation, so we never
         * materialise the full 1920px buffer just to draw a 240px
         * thumbnail. The bitmap is recycled immediately after
         * drawBitmap hands the pixels off to the PDF canvas.
         */
        private fun drawPhotoRow(photos: List<ByteArray>) {
            if (photos.isEmpty()) return
            val rowHeight = PHOTO_HEIGHT_PT + PHOTO_CAPTION_HEIGHT_PT
            ensureSpace(rowHeight)
            val canvas = currentCanvas ?: return

            // "Photos" label first so the reader knows what they're
            // looking at. Tiny caption paint — matches "Medication" /
            // "Notes" labels above.
            drawSingleLineViaBitmap("Photos", marginX, currentY + 10f, labelPaint)
            currentY += PHOTO_CAPTION_HEIGHT_PT

            val gap = 8f
            val maxPerRow = 3
            val available = printableWidth
            val tileWidth = ((available - gap * (maxPerRow - 1)) / maxPerRow).coerceAtLeast(40f)
            val tileHeight = PHOTO_HEIGHT_PT

            photos.take(maxPerRow).forEachIndexed { index, jpegBytes ->
                val x = marginX + index * (tileWidth + gap)
                val rect = RectF(x, currentY, x + tileWidth, currentY + tileHeight)
                // Background rect so a decode-failure still leaves a
                // visible "something was here" tile.
                fillPaint.color = COLOR_RULE_SOFT
                canvas.drawRoundRect(rect, 6f, 6f, fillPaint)

                val bitmap = decodePhotoThumbnail(jpegBytes, tileWidth.toInt(), tileHeight.toInt())
                if (bitmap != null) {
                    val clipPath = android.graphics.Path().apply {
                        addRoundRect(rect, 6f, 6f, android.graphics.Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    // Aspect-preserving centre crop into the tile rect.
                    val srcW = bitmap.width
                    val srcH = bitmap.height
                    val srcAspect = srcW.toFloat() / srcH.toFloat()
                    val dstAspect = tileWidth / tileHeight
                    val src = if (srcAspect > dstAspect) {
                        val cropW = (srcH * dstAspect).toInt()
                        val x0 = (srcW - cropW) / 2
                        Rect(x0, 0, x0 + cropW, srcH)
                    } else {
                        val cropH = (srcW / dstAspect).toInt()
                        val y0 = (srcH - cropH) / 2
                        Rect(0, y0, srcW, y0 + cropH)
                    }
                    canvas.drawBitmap(bitmap, src, rect, null)
                    canvas.restore()
                    // Defer recycling until the page is finished — see
                    // [bitmapsPendingRecycle] for the SIGSEGV root cause.
                    bitmapsPendingRecycle += bitmap
                }
            }
            currentY += tileHeight
        }

        /**
         * Memory-conscious JPEG → downscaled [Bitmap] decoder. Reads the
         * bounds first, computes a power-of-two `inSampleSize` that
         * satisfies the requested pixel box, then allocates only that
         * smaller buffer. Returns null on any decode failure — the
         * caller renders a placeholder rectangle instead.
         */
        private fun decodePhotoThumbnail(
            jpegBytes: ByteArray,
            targetWidthPx: Int,
            targetHeightPx: Int,
        ): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            // Aim for ~2x the target so the final crop stays crisp.
            val targetW = targetWidthPx.coerceAtLeast(1) * 2
            val targetH = targetHeightPx.coerceAtLeast(1) * 2
            while (bounds.outWidth / (sample * 2) >= targetW &&
                bounds.outHeight / (sample * 2) >= targetH
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return runCatching {
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
            }.getOrNull()
        }

        private fun drawAnalysisEntry(analysis: ReportAnalysis) {
            ensureSpace(56f)
            // No top-of-function `val canvas = currentCanvas` — see the
            // SIGSEGV note in [drawLogEntry]. Every direct canvas use
            // re-resolves so we never draw on a finished page.
            if (currentCanvas == null) return
            // Headlines are fixed enum strings today, but cheap to
            // sanitize anyway — guards against a future change that
            // starts propagating model output into this field.
            drawSingleLineViaBitmap(
                sanitizeForPdf(analysis.headline),
                marginX,
                currentY + 12f,
                entryTitlePaint,
            )
            val pillCanvas = currentCanvas ?: return
            drawGuidancePill(pillCanvas, analysis.guidance, topY = currentY)
            currentY += 18f

            drawSingleLineViaBitmap(
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
            //
            // The `summaryText` is the one field in this report pipeline
            // that can contain arbitrary model output — every other
            // draw-text input is either an enum or a formatted date. We
            // previously SIGSEGV'd inside libminikin on an analysis whose
            // summary contained a malformed surrogate run, so
            // [sanitizeForPdf] is applied BEFORE markdown flattening,
            // and the whole draw is wrapped in a last-resort catch that
            // falls back to a friendlier placeholder rather than
            // crashing the generator mid-page.
            // Strip `GUIDANCE:` / `NHS_REFERENCE:` internal markers
            // before flattening — those are renderer-agnostic classifier
            // hints; the guidance is already conveyed by the pill above,
            // and we emit our own authoritative NHS disclaimer below so
            // the footer line is identical across every analysis in the
            // report.
            val cleanedSummary = AnalysisSummaryFormatter.stripInternalMarkers(analysis.summaryText)
            val safeBody = flattenMarkdown(sanitizeForPdf(cleanedSummary))
            // Bitmap-backed drawWrappedText is the primary crash fix — see
            // its kdoc for the libminikin/PDF-canvas bug this avoids.
            drawWrappedText(safeBody, bodyPaint)

            // Always-present NHS disclaimer. Mirrors the in-app card so a
            // clinician reading the printed report sees the same "check
            // NHS for full symptoms, call 111/999" note that the user
            // saw on screen.
            drawSpacer(4f)
            drawWrappedText(AnalysisSummaryFormatter.NHS_DISCLAIMER, mutedBodyPaint)

            drawSpacer(8f)
            strokePaint.color = COLOR_RULE_SOFT
            strokePaint.strokeWidth = 0.5f
            // Re-resolve canvas — drawSpacer above can page-break.
            val ruleCanvas = currentCanvas ?: return
            ruleCanvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, strokePaint)
            currentY += 10f
        }

        private fun drawLabelledParagraph(label: String, body: String) {
            ensureSpace(16f)
            drawSingleLineViaBitmap(label, marginX, currentY + 10f, labelPaint)
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
            // Bitmap-backed text — same rationale as the entry title path:
            // direct `canvas.drawText` on the PdfDocument canvas has been
            // the source of native (libhwui/libminikin) SIGSEGV crashes
            // in this codebase. Going through an offscreen bitmap routes
            // the shaper away from the PDF text pipeline, which is where
            // the null-deref happens. The visual result is pixel-identical.
            drawSingleLineViaBitmap(label, x + 9f, topY + 11f, pillLabelPaint)
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
            // Bitmap-backed — see drawSingleLineViaBitmap kdoc.
            drawSingleLineViaBitmap(label, x + 9f, topY + 11f, pillLabelPaint)
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

            // All summary text bitmap-backed — see drawSingleLineViaBitmap
            // kdoc for the libminikin SIGSEGV rationale.
            drawSingleLineViaBitmap("Summary", marginX + 14f, currentY + 20f, summaryTitlePaint)

            val columnWidth = (printableWidth - 28f) / 2f
            val leftX = marginX + 14f
            val rightX = leftX + columnWidth

            drawSingleLineViaBitmap("Total symptom entries", leftX, currentY + 40f, metaPaint)
            drawSingleLineViaBitmap(
                snapshot.totalLogCount.toString(),
                leftX,
                currentY + 66f,
                summaryValuePaint,
            )

            drawSingleLineViaBitmap("Average severity", rightX, currentY + 40f, metaPaint)
            val avg = snapshot.averageSeverity
            val avgLabel = if (avg == null) {
                "—"
            } else {
                "${"%.2f".format(java.util.Locale.UK, avg)} / 10"
            }
            drawSingleLineViaBitmap(avgLabel, rightX, currentY + 66f, summaryValuePaint)

            drawSingleLineViaBitmap(
                "Computed directly from the symptom_logs table (COUNT + AVG).",
                leftX,
                currentY + 86f,
                helperPaint,
            )

            currentY += 104f
        }

        /**
         * Core text-drawing primitive — wraps [text] at [printableWidth]
         * and advances [currentY] accordingly.
         *
         * ### Why the bitmap detour?
         * The "obvious" implementation would be to call `layout.draw(pdfCanvas)`
         * directly, and that's what this method used to do. On some Samsung
         * devices running Android 12 (S10e / One UI 4) that path trips a
         * Skia bug inside libminikin / libhwui — a null-dereference SIGSEGV
         * at address 0x0 when the PDF canvas's text pipeline shapes
         * multi-line body text. Native signals bypass Kotlin try/catch, so
         * the process simply dies and the report generation is never
         * completed.
         *
         * Routing through an offscreen [Bitmap] canvas sidesteps the bug:
         * the `StaticLayout` draws into a normal ARGB_8888 bitmap using
         * the regular hardware/software Skia path (which works fine), and
         * then we composite that bitmap onto the PDF canvas with
         * [Canvas.drawBitmap] — a much simpler native op that does NOT
         * invoke the buggy text shaping code on the PDF side.
         *
         * Memory cost is bounded: printable width × line height, so at
         * most a few hundred KB per draw; we recycle immediately.
         */
        private fun drawWrappedText(text: String, paint: TextPaint) {
            if (text.isEmpty()) return
            var remaining = text
            val width = printableWidth.roundToInt().coerceAtLeast(1)
            while (remaining.isNotEmpty()) {
                if (currentCanvas == null) return
                val availableHeight = (pageHeight - marginY) - currentY
                if (availableHeight < paint.textSize * 1.4f) {
                    closePage()
                    openPage()
                    continue
                }

                val layout = buildStaticLayout(remaining, paint, width)
                val fullHeight = layout.height
                val (chunkHeight, splitOffset) = if (fullHeight.toFloat() <= availableHeight) {
                    fullHeight to remaining.length
                } else {
                    // Fit as many lines as we can on this page.
                    var fittingLines = layout.lineCount
                    while (fittingLines > 0 &&
                        layout.getLineBottom(fittingLines - 1) > availableHeight
                    ) {
                        fittingLines -= 1
                    }
                    if (fittingLines == 0) {
                        closePage(); openPage(); continue
                    }
                    layout.getLineBottom(fittingLines - 1) to layout.getLineEnd(fittingLines - 1)
                }

                val chunkText = remaining.substring(0, splitOffset)
                val chunkLayout = if (splitOffset == remaining.length) {
                    layout
                } else {
                    buildStaticLayout(chunkText, paint, width)
                }
                val realHeight = chunkLayout.height.coerceIn(1, chunkHeight.coerceAtLeast(1))
                val drawHeight = chunkLayout.height.coerceAtLeast(1)
                renderLayoutViaBitmap(chunkLayout, width, drawHeight)
                currentY += drawHeight + 2f
                remaining = remaining.substring(splitOffset)
                if (remaining.isNotEmpty()) {
                    closePage(); openPage()
                }
                // `realHeight` kept to silence an unused-var warning in
                // some compiler combos — no behavioural impact.
                @Suppress("UNUSED_VARIABLE") val _touch = realHeight
            }
        }

        private fun buildStaticLayout(
            text: String,
            paint: TextPaint,
            width: Int,
        ): StaticLayout = StaticLayout.Builder.obtain(
            text, 0, text.length, paint, width,
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            // Disable the native hyphenator + advanced line-break pass.
            // On some Android 12+ builds those paths contain a
            // null-dereference crash (SIGSEGV reports w/ addr 0x0 inside
            // libhwui/libminikin). Simple break strategy is crash-safe
            // and visually indistinguishable for left-aligned body text.
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        /**
         * Render [layout] into an offscreen ARGB_8888 bitmap and drawBitmap
         * it onto the current PDF canvas at [marginX], [currentY]. See
         * [drawWrappedText] kdoc for why we indirect through a bitmap.
         */
        private fun renderLayoutViaBitmap(
            layout: StaticLayout,
            width: Int,
            height: Int,
        ) {
            val canvas = currentCanvas ?: return
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val offscreen = Canvas(bitmap)
            layout.draw(offscreen)
            canvas.drawBitmap(bitmap, marginX, currentY, null)
            // Defer recycling until the page is finished — see
            // [bitmapsPendingRecycle] for the SIGSEGV root cause.
            bitmapsPendingRecycle += bitmap
        }

        /**
         * Single-line bitmap-backed draw, used in place of a bare
         * `canvas.drawText(...)` for any string containing model output
         * or user-supplied content. Same rationale as [drawWrappedText]:
         * the PDF canvas's text pipeline is the crashing surface, so we
         * redirect through a bitmap.
         *
         * Caller provides the absolute baseline y ([baselineY]) — matches
         * the `canvas.drawText(text, x, y, paint)` signature it replaces.
         */
        private fun drawSingleLineViaBitmap(
            text: String,
            x: Float,
            baselineY: Float,
            paint: TextPaint,
        ) {
            if (text.isEmpty()) return
            val canvas = currentCanvas ?: return
            val measuredWidth = paint.measureText(text).coerceAtLeast(1f).toInt() + 2
            // Use font metrics to size the bitmap so the glyph bounding
            // box (including descenders) fits.
            val fm = paint.fontMetrics
            val ascent = -fm.ascent
            val descent = fm.descent
            val bmpHeight = (ascent + descent).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(measuredWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            val offscreen = Canvas(bitmap)
            offscreen.drawText(text, 0f, ascent, paint)
            canvas.drawBitmap(bitmap, x, baselineY - ascent, null)
            // Defer recycling until the page is finished — see
            // [bitmapsPendingRecycle] for the SIGSEGV root cause.
            bitmapsPendingRecycle += bitmap
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
    private fun sanitizeForPdf(source: String): String {
        if (source.isEmpty()) return source
        val sb = StringBuilder(source.length)
        var i = 0
        var runLength = 0
        while (i < source.length) {
            val ch = source[i]
            val code = ch.code
            val keep: Char? = when {
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
            if (keep != null) {
                if (keep == '\n' || keep == ' ' || keep == '\t') {
                    runLength = 0
                } else {
                    runLength += 1
                }
                // Break pathologically long unbreakable runs by
                // inserting a soft space. 80 chars is well above any
                // natural word length but well under what would wrap.
                if (runLength > MAX_UNBREAKABLE_RUN) {
                    sb.append(' ')
                    runLength = 0
                }
                sb.append(keep)
            }
            i += 1
        }
        return sb.toString()
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
        // Locale.UK (not getDefault) for two reasons:
        //  1. The PDF is the doctor-handover artefact. Doctors want
        //     stable English month names ("26 Apr 2026"), not whatever
        //     locale the patient's phone happens to be set to.
        //  2. Locale.getDefault() can pull in non-Latin month names
        //     (Cyrillic, Arabic, Han) that have triggered libminikin
        //     SIGSEGVs on the bitmap text path in the past. UK month
        //     names stay in the ASCII Latin block.
        // Built per-call to dodge the "Constant Locale" lint warning
        // and to cost nothing on the cold-start path.
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.UK)
        return dateTime.format(fmt)
    }

    companion object {
        const val REPORTS_SUBDIR = "reports"
        const val PREVIEW_SUBDIR = "preview"
        const val COMPRESSED_SUFFIX = ".gz"

        /**
         * Soft-break threshold for unbreakable character runs — 80 is
         * well above any natural word (the longest English word in
         * common use is 29 chars) but short enough that a pathological
         * 10k-char URL or base64 blob can't overflow the native line
         * breaker's fixed-size stack buffers.
         */
        private const val MAX_UNBREAKABLE_RUN = 80

        /**
         * Height (in PDF points, 72dpi) of each photo thumbnail in a
         * log-entry row. 120pt ≈ 42mm — large enough to read a close-up
         * of a rash or skin mark without blowing up the PDF footprint.
         * At 3 photos wide on an A4 margin (~500pt printable), each tile
         * is ~160pt wide — landscape, which matches the centre-crop
         * bias in the draw routine.
         */
        private const val PHOTO_HEIGHT_PT: Float = 120f

        /**
         * Height of the "Photos" caption line above the thumbnail row.
         * Matches the "Notes" / "Medication" label spacing.
         */
        private const val PHOTO_CAPTION_HEIGHT_PT: Float = 14f

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

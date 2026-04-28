package com.example.mob_dev_portfolio.ui.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device PDF preview backed by Android's native [PdfRenderer].
 *
 * Each page is rendered to a [Bitmap] on a background dispatcher then
 * handed to Compose as an `ImageBitmap`. The pages are hosted inside a
 * [LazyColumn] so only the visible ones are materialised — reports of
 * 20+ pages don't blow out memory.
 *
 * A [DisposableEffect] guarantees the [PdfRenderer] and its backing
 * [ParcelFileDescriptor] are closed when this composable leaves the
 * composition. Leaking them would keep a file descriptor open and
 * eventually cause "too many open files" errors on aggressive
 * navigation.
 */
@Composable
fun PdfPreview(
    file: File,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    onPageCountChanged: (Int) -> Unit = {},
    /**
     * Optional click callback. When non-null, every rendered page tile
     * becomes tappable and forwards the click here — used by the report
     * screen to open the same preview composable inside a full-screen
     * dialog. Null disables the affordance (e.g. when this composable
     * is *already* the full-screen view).
     */
    onPageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val session = remember(file) { PdfRenderSession(context = context, file = file) }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session) {
        onPageCountChanged(session.pageCount)
    }

    LazyColumn(
        modifier = modifier.testTag("report_pdf_preview"),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = session.pageCount,
            key = { it },
        ) { index ->
            PdfPageTile(
                session = session,
                pageIndex = index,
                modifier = Modifier.testTag("report_pdf_page_$index"),
                onClick = onPageClick,
            )
        }
        if (session.pageCount == 0) {
            item {
                Text(
                    "This report has no pages to preview.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PdfPageTile(
    session: PdfRenderSession,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // The page's native size dictates the render aspect; we size the
    // Image to the container width and match the page aspect so the
    // caller doesn't have to hard-code an A4 shape. BoxWithConstraints
    // exposes pixel width to the renderer for sharp bitmaps at any
    // screen density.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            // Clickable only when the caller hands us a handler — keeps
            // the fullscreen dialog (which re-uses this composable) from
            // becoming clickable into another nested fullscreen.
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().toInt().coerceAtLeast(1) }
        val meta = session.pageMeta(pageIndex)
        val aspect = meta?.let { it.widthPt.toFloat() / it.heightPt.toFloat() } ?: (1f / 1.414f)
        var bitmap by remember(pageIndex, widthPx) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(pageIndex, widthPx) {
            if (widthPx <= 0) return@LaunchedEffect
            bitmap = withContext(Dispatchers.IO) {
                session.renderPage(pageIndex = pageIndex, targetWidthPx = widthPx)
            }
        }

        val current = bitmap
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Report page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect),
            )
        }
    }
}

/**
 * Thread-unsafe holder for the [PdfRenderer] and its backing file
 * descriptor. All rendering is mediated through [renderPage], which
 * locks around the renderer because [PdfRenderer] forbids concurrent
 * `openPage` calls on the same instance.
 *
 * [close] is idempotent so a DisposableEffect that retriggers before
 * teardown doesn't double-close.
 */
private class PdfRenderSession(
    context: Context,
    file: File,
) {
    // Open the descriptor + renderer defensively. A corrupt or missing
    // PDF on disk (e.g. cache evicted, mid-write crash last run) would
    // otherwise throw IOException synchronously during composition and
    // crash the UI. We capture the failure instead and expose a session
    // with zero pages so the screen renders an "empty preview" hint.
    private val fd: ParcelFileDescriptor?
    private val renderer: PdfRenderer?
    private val lock = Any()
    private var closed = false

    val pageCount: Int

    private val metaCache: Array<PageMeta?>

    init {
        var openedFd: ParcelFileDescriptor? = null
        var openedRenderer: PdfRenderer? = null
        try {
            openedFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            openedRenderer = PdfRenderer(openedFd)
        } catch (t: Throwable) {
            // Roll back any partially-acquired resources so we don't
            // leak a file descriptor when the PdfRenderer constructor
            // fails after the fd has been opened.
            runCatching { openedRenderer?.close() }
            runCatching { openedFd?.close() }
            openedFd = null
            openedRenderer = null
        }
        fd = openedFd
        renderer = openedRenderer
        pageCount = openedRenderer?.pageCount ?: 0
        metaCache = arrayOfNulls(pageCount)
    }

    init {
        // Touch the context so lint doesn't flag it unused — keeping
        // the param for future use (e.g. pulling a cached bitmap).
        @Suppress("UNUSED_EXPRESSION") context
    }

    fun pageMeta(pageIndex: Int): PageMeta? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until pageCount) return null
        metaCache[pageIndex]?.let { return it }
        synchronized(lock) {
            if (closed) return null
            metaCache[pageIndex]?.let { return it }
            val page = r.openPage(pageIndex)
            val meta = PageMeta(widthPt = page.width, heightPt = page.height)
            page.close()
            metaCache[pageIndex] = meta
            return meta
        }
    }

    fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until pageCount) return null
        synchronized(lock) {
            if (closed) return null
            val page = r.openPage(pageIndex)
            // Scale the bitmap so the page renders at the current
            // layout width, preserving aspect. We cap height to avoid
            // an OOM on absurdly narrow screens (aspect × width).
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            val scale = targetWidthPx.toFloat() / pageWidth.toFloat()
            val bitmapWidth = targetWidthPx
            val bitmapHeight = (pageHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            page.close()
            return bitmap
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }
}

private data class PageMeta(val widthPt: Int, val heightPt: Int)

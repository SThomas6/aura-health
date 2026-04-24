package com.example.mob_dev_portfolio.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Multi-series line chart shared by the Home preview card and the
 * fullscreen Trends page.
 *
 * Every series is rendered in its own colour and normalised to the
 * chart's vertical extent using its own min/max (carried on the series
 * record). That matters because the user can co-plot severity (1–10),
 * humidity (0–100), steps (0–20 000) and sleep minutes (0–600) on the
 * same axis — without per-series normalisation the severity line would
 * render as a flat strip at the bottom next to a ten-thousand-step
 * spike. The legend surfaces the real min/max so the user can still
 * read "0.6 on this line = ~12 000 steps".
 *
 * Null buckets render as gaps (path is lifted + resumed), which is
 * what we want for sparse symptom logs on a 6-month view.
 */
@Composable
fun TrendLineChart(
    series: List<TrendSeries>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp,
    compact: Boolean = false,
) {
    if (series.isEmpty() || series.all { !it.hasData }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                if (compact) "No data"
                else "No data in this window yet.\nLog a symptom or connect Health Connect to see trends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val density = LocalDensity.current
    val strokePx = with(density) { strokeWidth.toPx() }
    val axisStrokePx = with(density) { 1.dp.toPx() }
    val dashPx = with(density) { 3.dp.toPx() }
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx), 0f)

    // Pre-compute the content description so TalkBack users get a
    // prose summary of what's on screen. Canvases aren't focusable
    // element-by-element, so a single description is the pragmatic
    // accessible entry point.
    val description = buildChartDescription(series)

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description },
    ) {
        val plotLeft = 0f
        val plotRight = size.width
        val plotTop = if (compact) 4f else 10f
        val plotBottom = size.height - (if (compact) 4f else 10f)
        val plotHeight = plotBottom - plotTop

        // Three horizontal gridlines at 0 / 0.5 / 1.0 of the normalised
        // range. Dashed so they sit behind the data without competing
        // for attention.
        if (!compact) {
            listOf(0f, 0.5f, 1f).forEach { frac ->
                val y = plotBottom - frac * plotHeight
                drawLine(
                    color = axisColor.copy(alpha = 0.5f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = axisStrokePx,
                    pathEffect = dashEffect,
                )
            }
        }

        // Each series draws left-to-right over the same bucket axis.
        // Assumes all series share the same bucket count — true by
        // construction in the ViewModel because every series is
        // aligned to the common Axis up front.
        val bucketCount = series.maxOf { it.buckets.size }.coerceAtLeast(2)
        val stepX = if (bucketCount > 1) (plotRight - plotLeft) / (bucketCount - 1) else 0f

        series.forEachIndexed { seriesIndex, s ->
            // Callers pre-resolve the palette in a @Composable scope so
            // we don't have to expose a composable colour resolver as a
            // lambda parameter (which the compiler can't verify is
            // invoked inside composition).
            val color = colors.getOrNull(seriesIndex) ?: colors.firstOrNull() ?: Color.Unspecified
            val rawMin = s.rawMin ?: 0.0
            val rawMax = s.rawMax ?: 1.0
            val range = (rawMax - rawMin).takeIf { it > 0.0 } ?: 1.0

            val path = Path()
            var pathStarted = false

            s.buckets.forEachIndexed { i, bucket ->
                val raw = bucket.rawValue
                if (raw == null) {
                    // Gap — reset the path so the next point starts a
                    // fresh line segment rather than drawing a big
                    // straight line across missing data.
                    pathStarted = false
                    return@forEachIndexed
                }
                val normalised = ((raw - rawMin) / range).toFloat().coerceIn(0f, 1f)
                val x = plotLeft + i * stepX
                // Flip: higher raw value = higher on screen (smaller y).
                val y = plotBottom - normalised * plotHeight

                if (!pathStarted) {
                    path.moveTo(x, y)
                    pathStarted = true
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Dots on every real sample so single-reading days are
            // still visible. A series with one non-null bucket draws
            // no line (a path-of-one has no length) — without dots the
            // chart silently renders as empty, which was the bug on the
            // Home preview card. Compact mode uses a smaller radius so
            // the preview stays visually calm.
            val dotRadius = if (compact) strokePx * 0.75f else strokePx * 0.9f
            s.buckets.forEachIndexed { i, bucket ->
                val raw = bucket.rawValue ?: return@forEachIndexed
                val normalised = ((raw - rawMin) / range).toFloat().coerceIn(0f, 1f)
                val x = plotLeft + i * stepX
                val y = plotBottom - normalised * plotHeight
                drawCircle(color = color, radius = dotRadius, center = Offset(x, y))
            }
        }
    }
}

private fun buildChartDescription(series: List<TrendSeries>): String {
    val parts = series.map { s ->
        val range = if (s.rawMin != null && s.rawMax != null)
            " ranging ${fmt(s.rawMin)}${s.units} to ${fmt(s.rawMax)}${s.units}"
        else ""
        "${s.label}$range"
    }
    return "Trend line chart showing ${parts.joinToString(", ")}."
}

private fun fmt(v: Double): String =
    if (v >= 100) String.format(Locale.getDefault(), "%.0f", v)
    else String.format(Locale.getDefault(), "%.1f", v)

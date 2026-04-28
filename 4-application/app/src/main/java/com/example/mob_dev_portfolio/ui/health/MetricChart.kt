package com.example.mob_dev_portfolio.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository.DataPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Unified chart renderer for the Home mini-card and the fullscreen
 * detail. The [style] dictates which visual the metric renders as:
 *
 *  - [ChartStyle.Bar] — cumulative metrics (steps, sleep, active kcal,
 *    exercise count). One bar per bucket.
 *  - [ChartStyle.Line] — trending point-in-time metrics (heart rate,
 *    resting HR, weight, SpO₂, respiratory rate, height, body fat).
 *
 * The rendering is hand-rolled with Compose [Canvas] — matches the
 * existing [com.example.mob_dev_portfolio.ui.home.HomeScreen] trend chart
 * style and avoids pulling in a third-party dep.
 *
 * ### Interactivity
 * Press-and-drag anywhere over the chart to inspect an individual bucket:
 * a vertical cursor line snaps to the nearest bucket, the point is
 * highlighted, and a tooltip bubble renders the formatted value and the
 * bucket's date/time. Lifting the finger clears the selection. The whole
 * pipeline is self-contained in this composable so both the mini-card and
 * the fullscreen detail get it for free.
 *
 * ### Axes
 * When [showAxis] is true the chart reserves a left gutter for 3 Y-tick
 * labels (min/mid/max) and a bottom strip for 4 X-tick labels. The gutter
 * widths are calibrated to the max label width produced by [metric] at
 * the current [range] so the plot area never jitters between frames. The
 * mini-card leaves [showAxis] false so the tiny 72.dp canvas stays pure
 * chart.
 */
enum class ChartStyle { Bar, Line }

/**
 * Which chart is best for a given record type. Cumulative "total over
 * time" metrics look right as bars; point-in-time observations read
 * better as lines.
 */
fun HealthConnectMetric.defaultChartStyle(): ChartStyle = when (this) {
    HealthConnectMetric.Steps,
    HealthConnectMetric.ActiveCaloriesBurned,
    HealthConnectMetric.ExerciseSession,
    HealthConnectMetric.SleepSession -> ChartStyle.Bar
    HealthConnectMetric.HeartRate,
    HealthConnectMetric.RestingHeartRate,
    HealthConnectMetric.OxygenSaturation,
    HealthConnectMetric.RespiratoryRate,
    HealthConnectMetric.Weight,
    HealthConnectMetric.Height,
    HealthConnectMetric.BodyFat -> ChartStyle.Line
}

/**
 * Compose-Canvas chart primitive. Call-site controls height, metric
 * context, and whether axes are drawn; this just draws and handles
 * press-to-inspect.
 */
@Composable
fun MetricChart(
    points: List<DataPoint>,
    accent: Color,
    style: ChartStyle,
    modifier: Modifier = Modifier,
    /**
     * Needed for value/time formatting in the tooltip and for deriving
     * the right axis-label cadence. Pass the owning metric through from
     * the call-site rather than having each card re-derive it.
     */
    metric: HealthConnectMetric = HealthConnectMetric.Steps,
    /**
     * Range is used to pick the right X-axis label format — hours for
     * a Day view, day-of-month for Week/Month, short month names for
     * Year. Defaulted to Week because that's what the Home mini-card
     * always renders.
     */
    range: HealthHistoryRepository.Range = HealthHistoryRepository.Range.Week,
    height: Dp = 120.dp,
    showAxis: Boolean = false,
) {
    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLabelStyle = MaterialTheme.typography.labelSmall.copy(color = axisColor)
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipFg = MaterialTheme.colorScheme.inverseOnSurface
    val tooltipValueStyle = MaterialTheme.typography.labelLarge.copy(color = tooltipFg)
    val tooltipCaptionStyle = MaterialTheme.typography.labelSmall.copy(color = tooltipFg.copy(alpha = 0.75f))
    val textMeasurer: TextMeasurer = rememberTextMeasurer()

    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No data in this range",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val maxValue = points.maxOf { it.value }
    val minValueRaw = points.minOf { it.value }
    val isAllZero = maxValue <= 0.0 && minValueRaw >= 0.0

    if (isAllZero) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No data logged yet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Pointer state — the index of the bucket the user is currently
    // pressing, or null when the finger is up. Recomposing on every
    // change lets the Canvas draw the cursor line + tooltip in lockstep
    // with the gesture.
    var selectedIndex: Int? by remember(points) { mutableStateOf(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points, showAxis) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectedIndex = bucketIndexAt(
                            down,
                            points.size,
                            size.width.toFloat(),
                            leftGutterPx = if (showAxis) AXIS_LEFT_GUTTER_PX else 0f,
                            rightPadPx = if (showAxis) AXIS_RIGHT_PAD_PX else 0f,
                            style = style,
                        )
                        // Scrub: keep updating the selection while the
                        // finger is down and moving. `positionChange()`
                        // returning non-zero guarantees a real drag
                        // rather than a jittery same-pixel report.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                selectedIndex = bucketIndexAt(
                                    change,
                                    points.size,
                                    size.width.toFloat(),
                                    leftGutterPx = if (showAxis) AXIS_LEFT_GUTTER_PX else 0f,
                                    rightPadPx = if (showAxis) AXIS_RIGHT_PAD_PX else 0f,
                                    style = style,
                                )
                            }
                        }
                        selectedIndex = null
                    }
                },
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val stroke = with(density) { 2.dp.toPx() }
            val cornerPx = with(density) { 4.dp.toPx() }
            val gapPx = with(density) { 4.dp.toPx() }

            // Gutters reserved for axis labels (only when showAxis is on).
            val leftGutterPx = if (showAxis) AXIS_LEFT_GUTTER_PX else 0f
            val rightPadPx = if (showAxis) AXIS_RIGHT_PAD_PX else 0f
            val bottomStripPx = if (showAxis) AXIS_BOTTOM_STRIP_PX else 0f
            // `plotLeft` carries different intent from the gutter width
            // (one is "where the plot starts", the other is "how wide
            // the left gutter is"); keeping the alias makes the
            // downstream geometry maths read in plot-coordinates rather
            // than gutter-arithmetic. Lint can't tell the difference
            // between value and intent — the @Suppress is targeted.
            val plotLeft = leftGutterPx
            val plotRight = canvasWidth - rightPadPx
            val plotTop = 0f
            val plotBottom = canvasHeight - bottomStripPx
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

            val yMin: Float
            val yMax: Float
            if (style == ChartStyle.Bar) {
                yMin = 0f
                yMax = maxValue.toFloat().coerceAtLeast(1f)
            } else {
                // For line charts we want a little visual padding around
                // the actual data range so the line doesn't kiss the
                // top/bottom edges.
                val range0 = (maxValue - minValueRaw).coerceAtLeast(1.0)
                val pad = range0 * 0.12
                yMin = (minValueRaw - pad).toFloat()
                yMax = (maxValue + pad).toFloat()
            }
            val plotRange = (yMax - yMin).coerceAtLeast(0.001f)

            fun yFor(value: Double): Float {
                val normalised = ((value - yMin) / plotRange).toFloat().coerceIn(0f, 1f)
                return plotBottom - (normalised * plotHeight)
            }

            // ── Y axis (if enabled) ──
            if (showAxis) {
                drawYAxis(
                    textMeasurer = textMeasurer,
                    style = axisLabelStyle,
                    metric = metric,
                    min = yMin.toDouble(),
                    max = yMax.toDouble(),
                    plotBottom = plotBottom,
                    leftGutterPx = leftGutterPx,
                    trackColor = trackColor,
                )
            }

            // ── Plot area ──
            // Bars / line positions are indexed across the plot area,
            // not the full canvas, so axis gutters reserve the space they
            // need cleanly.
            when (style) {
                ChartStyle.Bar -> {
                    val count = points.size
                    val totalGap = gapPx * (count - 1).coerceAtLeast(0)
                    val barWidth = ((plotWidth - totalGap) / count).coerceAtLeast(1f)
                    points.forEachIndexed { index, p ->
                        val x = plotLeft + index * (barWidth + gapPx)
                        if (p.value == 0.0) {
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset(x, plotBottom - 4f),
                                size = Size(barWidth, 4f),
                                cornerRadius = CornerRadius(cornerPx, cornerPx),
                            )
                        } else {
                            val top = yFor(p.value)
                            val h = (plotBottom - top).coerceAtLeast(4f)
                            val isSelected = index == selectedIndex
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        if (isSelected) accent else accent,
                                        if (isSelected) accent.copy(alpha = 0.85f)
                                        else accent.copy(alpha = 0.55f),
                                    ),
                                    startY = top,
                                    endY = plotBottom,
                                ),
                                topLeft = Offset(x, top),
                                size = Size(barWidth, h),
                                cornerRadius = CornerRadius(cornerPx, cornerPx),
                            )
                        }
                    }
                }
                ChartStyle.Line -> {
                    val count = points.size
                    val step = if (count <= 1) plotWidth else plotWidth / (count - 1)
                    val linePath = Path()
                    val fillPath = Path()
                    var hasAny = false
                    points.forEachIndexed { index, p ->
                        if (p.value == 0.0) return@forEachIndexed
                        val x = plotLeft + index * step
                        val y = yFor(p.value)
                        if (!hasAny) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, plotBottom)
                            fillPath.lineTo(x, y)
                            hasAny = true
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    if (hasAny) {
                        val lastIdx = points.indexOfLast { it.value != 0.0 }
                        val lastX = plotLeft + lastIdx * step
                        fillPath.lineTo(lastX, plotBottom)
                        fillPath.close()
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.28f), Color.Transparent),
                                startY = plotTop,
                                endY = plotBottom,
                            ),
                        )
                        drawPath(
                            path = linePath,
                            color = accent,
                            style = Stroke(width = stroke),
                        )
                        // Tiny markers at each concrete sample; highlight
                        // the selected one.
                        points.forEachIndexed { index, p ->
                            if (p.value == 0.0) return@forEachIndexed
                            val x = plotLeft + index * step
                            val y = yFor(p.value)
                            val r = if (index == selectedIndex) stroke * 2.2f else stroke * 1.2f
                            drawCircle(color = accent, radius = r, center = Offset(x, y))
                        }
                    }
                }
            }

            // Baseline rule at the bottom of the plot for reference.
            drawLine(
                color = trackColor,
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 1f,
            )

            // ── X axis (if enabled) ──
            if (showAxis) {
                drawXAxis(
                    textMeasurer = textMeasurer,
                    style = axisLabelStyle,
                    points = points,
                    plotLeft = plotLeft,
                    plotRight = plotRight,
                    plotBottom = plotBottom,
                    range = range,
                )
            }

            // ── Cursor + tooltip ──
            val sel = selectedIndex
            if (sel != null && sel in points.indices) {
                val count = points.size
                val xCenter = when (style) {
                    ChartStyle.Bar -> {
                        val totalGap = gapPx * (count - 1).coerceAtLeast(0)
                        val barWidth = ((plotWidth - totalGap) / count).coerceAtLeast(1f)
                        plotLeft + sel * (barWidth + gapPx) + barWidth / 2f
                    }
                    ChartStyle.Line -> {
                        val step = if (count <= 1) plotWidth else plotWidth / (count - 1)
                        plotLeft + sel * step
                    }
                }
                val point = points[sel]
                val yAtPoint = if (point.value == 0.0) plotBottom else yFor(point.value)

                // Dotted vertical indicator through the selected bucket.
                drawLine(
                    color = accent.copy(alpha = 0.8f),
                    start = Offset(xCenter, plotTop),
                    end = Offset(xCenter, plotBottom),
                    strokeWidth = 1f,
                )

                drawTooltip(
                    textMeasurer = textMeasurer,
                    valueStyle = tooltipValueStyle,
                    captionStyle = tooltipCaptionStyle,
                    bg = tooltipBg,
                    valueText = formatTooltipValue(metric, point.value),
                    captionText = formatTooltipCaption(point.bucketStart, range),
                    anchorX = xCenter,
                    anchorY = yAtPoint,
                    plotLeft = plotLeft,
                    plotRight = plotRight,
                )
            }
        }
    }
}

// ── Axis-label geometry constants ────────────────────────────────────
// Fixed px values so the plot area is stable across frames. These are
// calibrated for labelSmall typography at default density — if we ever
// make the chart font-scale-aware, convert these to Dp + density.

private const val AXIS_LEFT_GUTTER_PX = 68f
private const val AXIS_RIGHT_PAD_PX = 8f
private const val AXIS_BOTTOM_STRIP_PX = 28f

// ── Helpers ──────────────────────────────────────────────────────────

/**
 * Map a pointer position to the index of the bucket it falls into.
 * Behaves consistently for bar and line charts — clamp to the plot area
 * and round-to-nearest so the user's finger doesn't need to be pixel-
 * perfect.
 */
private fun bucketIndexAt(
    change: PointerInputChange,
    bucketCount: Int,
    canvasWidth: Float,
    leftGutterPx: Float,
    rightPadPx: Float,
    style: ChartStyle,
): Int? {
    if (bucketCount <= 0) return null
    // Same naming-vs-value rationale as in [Chart] — see the kdoc-style
    // comment there for why the alias survives the lint hint.
    val plotLeft = leftGutterPx
    val plotRight = canvasWidth - rightPadPx
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val x = change.position.x.coerceIn(plotLeft, plotRight) - plotLeft
    return when (style) {
        ChartStyle.Bar -> {
            val slot = plotWidth / bucketCount
            (x / slot).toInt().coerceIn(0, bucketCount - 1)
        }
        ChartStyle.Line -> {
            if (bucketCount == 1) 0
            else {
                val step = plotWidth / (bucketCount - 1)
                ((x / step) + 0.5f).toInt().coerceIn(0, bucketCount - 1)
            }
        }
    }
}

/**
 * Draws 3 evenly-spaced Y-tick labels on the left gutter plus a thin
 * horizontal rule at each tick to aid visual scanning. Labels are
 * right-aligned against the plot's left edge.
 */
private fun DrawScope.drawYAxis(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    metric: HealthConnectMetric,
    min: Double,
    max: Double,
    plotBottom: Float,
    leftGutterPx: Float,
    trackColor: Color,
) {
    val ticks = 3
    val range0 = (max - min).coerceAtLeast(0.001)
    repeat(ticks) { i ->
        val fraction = i.toDouble() / (ticks - 1)
        val value = min + fraction * range0
        // The plot's top edge is y = 0, so `(plotBottom - 0) * fraction`
        // collapses to `plotBottom * fraction` — saves a wasted operand.
        val y = plotBottom - (fraction.toFloat() * plotBottom)
        // Light grid line through each tick — breaks visually noisy
        // single-colour plots into readable thirds.
        drawLine(
            color = trackColor.copy(alpha = 0.5f),
            start = Offset(leftGutterPx, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        val text = HealthMetricFormat.formatValue(metric, value)
        val layout = textMeasurer.measure(
            text = text,
            style = style.copy(textAlign = TextAlign.End),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (leftGutterPx - 6f).toInt().coerceAtLeast(1)),
        )
        val textX = (leftGutterPx - 6f - layout.size.width).coerceAtLeast(0f)
        val textY = (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
        drawText(layout, topLeft = Offset(textX, textY))
    }
}

/**
 * Draws 4 X-tick labels underneath the plot area. The labels are picked
 * at evenly-spaced bucket indices and formatted based on [range] — we
 * lean on [formatTickCaption] so the label style matches the tooltip.
 */
private fun DrawScope.drawXAxis(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    points: List<DataPoint>,
    plotLeft: Float,
    plotRight: Float,
    plotBottom: Float,
    range: HealthHistoryRepository.Range,
) {
    if (points.isEmpty()) return
    val ticks = 4.coerceAtMost(points.size)
    val plotWidth = plotRight - plotLeft
    repeat(ticks) { i ->
        val fraction = if (ticks == 1) 0f else i.toFloat() / (ticks - 1)
        val idx = (fraction * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
        val text = formatTickCaption(points[idx].bucketStart, range)
        val layout = textMeasurer.measure(text = text, style = style)
        val x = plotLeft + fraction * plotWidth
        val textX = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
        val textY = plotBottom + 6f
        drawText(layout, topLeft = Offset(textX, textY))
    }
}

/**
 * Draw a tooltip pill above the anchor point (or below if the anchor
 * is near the top). Keeps the pill fully inside the plot horizontally.
 */
private fun DrawScope.drawTooltip(
    textMeasurer: TextMeasurer,
    valueStyle: TextStyle,
    captionStyle: TextStyle,
    bg: Color,
    valueText: String,
    captionText: String,
    anchorX: Float,
    anchorY: Float,
    plotLeft: Float,
    plotRight: Float,
) {
    val valueLayout = textMeasurer.measure(valueText, valueStyle)
    val captionLayout = textMeasurer.measure(captionText, captionStyle)
    val padH = 10f
    val padV = 6f
    val innerSpacing = 2f
    val width = maxOf(valueLayout.size.width, captionLayout.size.width) + padH * 2
    val height = valueLayout.size.height + captionLayout.size.height + padV * 2 + innerSpacing

    // Prefer above the point; flip below if there's no room. The plot
    // always starts at y = 0, so the "fits above" check just needs to
    // see if the tooltip's top edge would be non-negative.
    val gap = 10f
    val preferAbove = anchorY - gap - height >= 0f
    val top = if (preferAbove) anchorY - gap - height else anchorY + gap

    val left = (anchorX - width / 2f)
        .coerceIn(plotLeft, (plotRight - width).coerceAtLeast(plotLeft))

    drawRoundRect(
        color = bg,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f, 8f),
    )
    drawText(
        valueLayout,
        topLeft = Offset(
            left + (width - valueLayout.size.width) / 2f,
            top + padV,
        ),
    )
    drawText(
        captionLayout,
        topLeft = Offset(
            left + (width - captionLayout.size.width) / 2f,
            top + padV + valueLayout.size.height + innerSpacing,
        ),
    )
}

/**
 * Tooltip value line — e.g. "8,432 steps", "62.1 bpm". Reuses the
 * canonical formatter so chart + card always agree.
 */
private fun formatTooltipValue(metric: HealthConnectMetric, value: Double): String {
    val formatted = HealthMetricFormat.formatValue(metric, value)
    val unit = HealthMetricFormat.unitLabel(metric)
    return "$formatted $unit"
}

/**
 * Tooltip subtitle — a human date/time stamp that varies with the
 * chart's range. Day shows hour of day; Week/Month shows weekday +
 * day-of-month; Year shows abbreviated month + year.
 */
private fun formatTooltipCaption(
    bucketStart: Instant,
    range: HealthHistoryRepository.Range,
): String {
    val zoned = bucketStart.atZone(ZoneId.systemDefault())
    return when (range) {
        HealthHistoryRepository.Range.Day ->
            zoned.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()))
        HealthHistoryRepository.Range.Week ->
            zoned.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        HealthHistoryRepository.Range.Month ->
            zoned.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        HealthHistoryRepository.Range.HalfYear ->
            zoned.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        HealthHistoryRepository.Range.Year ->
            zoned.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
    }
}

/**
 * X-axis tick caption — shorter than the tooltip caption because it has
 * to fit 4-across without overlapping. No weekday name for Year, no
 * month name for Day.
 */
private fun formatTickCaption(
    bucketStart: Instant,
    range: HealthHistoryRepository.Range,
): String {
    val zoned = bucketStart.atZone(ZoneId.systemDefault())
    return when (range) {
        HealthHistoryRepository.Range.Day ->
            zoned.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        HealthHistoryRepository.Range.Week ->
            zoned.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
        HealthHistoryRepository.Range.Month ->
            zoned.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        HealthHistoryRepository.Range.HalfYear ->
            zoned.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        HealthHistoryRepository.Range.Year ->
            zoned.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
    }
}

/**
 * The accent colour used for a metric's chart + summary number. Picked
 * per category so the dashboard feels colourful without clashing.
 */
@Composable
fun HealthConnectMetric.accentColor(): Color = when (this) {
    HealthConnectMetric.Steps -> Color(0xFF2DB67A)
    HealthConnectMetric.ActiveCaloriesBurned -> Color(0xFFE56B3F)
    HealthConnectMetric.ExerciseSession -> Color(0xFFD4475A)
    HealthConnectMetric.HeartRate -> Color(0xFFE84B5E)
    HealthConnectMetric.RestingHeartRate -> Color(0xFFB4354A)
    HealthConnectMetric.OxygenSaturation -> Color(0xFF3BA6D1)
    HealthConnectMetric.RespiratoryRate -> Color(0xFF6C8CD4)
    HealthConnectMetric.SleepSession -> Color(0xFF6C4BB6)
    HealthConnectMetric.Weight -> Color(0xFFB8812A)
    HealthConnectMetric.Height -> Color(0xFF7A6C45)
    HealthConnectMetric.BodyFat -> Color(0xFFA05C8A)
}

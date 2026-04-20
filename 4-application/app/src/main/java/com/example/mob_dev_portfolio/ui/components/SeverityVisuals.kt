package com.example.mob_dev_portfolio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import com.example.mob_dev_portfolio.ui.theme.AuraSeverityGradient
import com.example.mob_dev_portfolio.ui.theme.severityColor

/**
 * Vertical severity edge that sits flush with the leading side of a card
 * to telegraph intensity at a glance. Cards that use it should leave
 * ~12dp of padding on the start edge so the bar doesn't collide with text.
 */
@Composable
fun SeverityEdge(
    severity: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(severityColor(severity)),
    )
}

/**
 * Compact severity chip rendered as a monospace "n/10" pill. The
 * background is a tinted mix of the severity colour and the surface so
 * the chip blends into the card while still reading as quantitative.
 */
@Composable
fun SeverityPill(
    severity: Int,
    modifier: Modifier = Modifier,
) {
    val base = severityColor(severity)
    val surface = MaterialTheme.colorScheme.surface
    // Flat 22% mix of severity × surface — matches the CSS
    // `color-mix(in oklch, ... 22%, var(--surface))` used on the
    // recent-log row in the prototype.
    val tinted = Color(
        red = base.red * 0.22f + surface.red * 0.78f,
        green = base.green * 0.22f + surface.green * 0.78f,
        blue = base.blue * 0.22f + surface.blue * 0.78f,
        alpha = 1f,
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tinted)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$severity/10",
            fontFamily = AuraMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Circular severity gauge — big numeric readout in the centre, an
 * arc whose colour tracks the severity, and a soft track behind it.
 * Used as the hero metric on the Home insights card.
 */
@Composable
fun SeverityRing(
    severity: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 120.dp,
    max: Int = 10,
    label: String = "of $max",
) {
    val clamped = severity.coerceIn(0, max)
    val fraction = clamped.toFloat() / max.toFloat()
    val arcColor = severityColor(clamped.coerceAtLeast(1))
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
            val stroke = 10.dp.toPx()
            val diameter = this.size.minDimension - stroke
            val topLeft = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = clamped.toString(),
                fontFamily = AuraMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                fontFamily = AuraMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Severity slider with a gradient track (mint → coral) and a custom
 * thumb that picks up the currently-selected severity colour. Wraps
 * Material 3's [Slider] so keyboard/TB semantics, haptics, and a11y
 * come for free; we just paint over the visuals.
 */
@Composable
fun SeveritySlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 10,
    enabled: Boolean = true,
) {
    val clamped = value.coerceIn(min, max)
    val thumbColor = severityColor(clamped)
    Box(modifier = modifier.fillMaxWidth()) {
        // Gradient track painted behind the slider. The transparent
        // Slider track lets the gradient show through.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .offset(y = 17.dp)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AuraSeverityGradient),
        )
        Slider(
            value = clamped.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = max - min - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Segmented 1..10 readout shown below the slider. Tapping a cell is an
 * alternate way to set the value — some users prefer snap-targets over
 * dragging, especially on small screens.
 */
@Composable
fun SeveritySegmentedRow(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 10,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in min..max) {
            val selected = i == value
            val bg = if (selected) severityColor(i) else MaterialTheme.colorScheme.surfaceContainer
            val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .border(
                        width = if (selected) 0.dp else 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onValueChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = i.toString(),
                    fontFamily = AuraMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = fg,
                )
            }
        }
    }
}

/** Horizontal rule that sits between sections of a form. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Gradient brush used by the Home hero and Analysis intro hero cards.
 * Exposed as a function so callers can compose it into other brushes
 * (the severity hero on Detail interpolates between this and a
 * severity colour, for example).
 */
@Composable
fun auraHeroGradient(): Brush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF0A2F28),
        MaterialTheme.colorScheme.primary,
        severityColor(2),
    ),
)

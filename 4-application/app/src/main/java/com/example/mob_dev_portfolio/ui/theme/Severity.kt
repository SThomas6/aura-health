package com.example.mob_dev_portfolio.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Maps a 1..10 severity level onto the brand severity scale. Clamps
 * out-of-range inputs rather than throwing — the data layer occasionally
 * hands us 0 for "not specified" and we still want a neutral colour back
 * instead of an index-out-of-bounds crash on a card row.
 */
fun severityColor(level: Int): Color {
    val clamped = level.coerceIn(1, AuraSeverityScale.size)
    return AuraSeverityScale[clamped - 1]
}

/**
 * Linear gradient that travels the full mint→coral arc. Driving the
 * severity slider track and the detail-hero background uses this brush
 * rather than the stepped scale, so the control reads as a continuum
 * while still snapping to integer values.
 */
val AuraSeverityGradient: Brush = Brush.horizontalGradient(AuraSeverityScale)

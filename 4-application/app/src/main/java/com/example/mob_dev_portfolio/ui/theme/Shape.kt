package com.example.mob_dev_portfolio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────────────────────────────
// Shapes — Material 3 Expressive radii lifted from the redesign's
// `--r-sm`/`--r-md`/`--r-lg`/`--r-xl` tokens. Cards and large surfaces
// lean on ExtraLarge; severity pills and segmented controls use Full.
// ──────────────────────────────────────────────────────────────────────
val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

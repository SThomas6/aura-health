package com.example.mob_dev_portfolio.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────
// Aura · mint-clinical palette (Material 3 Expressive)
//
// Lifted 1:1 from `Aura Redesign.html`. The design reviewer landed on a
// teal/mint primary (#0E7C66) paired with a deep ink surface (#0A2F28),
// explicitly because it "feels fresh, medical, trustworthy — not tech-bro
// and not spa-kitsch". Every token maps directly to a Material 3 colour
// role so dynamic-colour users degrade gracefully.
// ──────────────────────────────────────────────────────────────────────

// Light
val AuraPrimary = Color(0xFF0E7C66)
val AuraOnPrimary = Color(0xFFFFFFFF)
val AuraPrimaryContainer = Color(0xFFB8ECDC)
val AuraOnPrimaryContainer = Color(0xFF002018)

val AuraSecondary = Color(0xFF4B635B)
val AuraOnSecondary = Color(0xFFFFFFFF)
val AuraSecondaryContainer = Color(0xFFCEE9DE)
val AuraOnSecondaryContainer = Color(0xFF08201A)

val AuraTertiary = Color(0xFF3E6374)
val AuraOnTertiary = Color(0xFFFFFFFF)
val AuraTertiaryContainer = Color(0xFFC2E8FC)
val AuraOnTertiaryContainer = Color(0xFF001F2A)

val AuraError = Color(0xFFB3261E)
val AuraOnError = Color(0xFFFFFFFF)
val AuraErrorContainer = Color(0xFFFFDAD6)
val AuraOnErrorContainer = Color(0xFF410002)

val AuraBackground = Color(0xFFF3FAF7)
val AuraOnBackground = Color(0xFF0A2F28)
val AuraSurface = Color(0xFFFFFFFF)
val AuraOnSurface = Color(0xFF0A2F28)
val AuraSurfaceVariant = Color(0xFFC4D7CE)
val AuraOnSurfaceVariant = Color(0xFF3F5A52)
val AuraOutline = Color(0xFF6F8A81)
val AuraOutlineVariant = Color(0xFFC4D7CE)

// Tonal elevation ladder — `surfaceContainerLow` through `…Highest` are
// the M3 "expressive" surface tokens the redesign leans on for the
// insights card, heatmap tiles and tag pills.
val AuraSurfaceContainerLowest = Color(0xFFFFFFFF)
val AuraSurfaceContainerLow = Color(0xFFF4F9F6)
val AuraSurfaceContainer = Color(0xFFEDF4F0)
val AuraSurfaceContainerHigh = Color(0xFFE5EFEA)
val AuraSurfaceContainerHighest = Color(0xFFDCE9E3)

// Dark
val AuraPrimaryDark = Color(0xFF6ED5BF)
val AuraOnPrimaryDark = Color(0xFF003A2C)
val AuraPrimaryContainerDark = Color(0xFF004C3C)
val AuraOnPrimaryContainerDark = Color(0xFFB8ECDC)

val AuraSecondaryDark = Color(0xFFB3CDBF)
val AuraOnSecondaryDark = Color(0xFF1A332A)
val AuraSecondaryContainerDark = Color(0xFF335149)
val AuraOnSecondaryContainerDark = Color(0xFFCEE9DE)

val AuraTertiaryDark = Color(0xFFA6CDDF)
val AuraOnTertiaryDark = Color(0xFF0B2A36)
val AuraTertiaryContainerDark = Color(0xFF214B5B)
val AuraOnTertiaryContainerDark = Color(0xFFC2E8FC)

val AuraErrorDark = Color(0xFFF2B8B5)
val AuraOnErrorDark = Color(0xFF601410)
val AuraErrorContainerDark = Color(0xFF8C1D18)
val AuraOnErrorContainerDark = Color(0xFFF9DEDC)

val AuraBackgroundDark = Color(0xFF0B1613)
val AuraOnBackgroundDark = Color(0xFFE6F4EE)
val AuraSurfaceDark = Color(0xFF121E1A)
val AuraOnSurfaceDark = Color(0xFFE6F4EE)
val AuraSurfaceVariantDark = Color(0xFF2E463F)
val AuraOnSurfaceVariantDark = Color(0xFFA7C1B8)
val AuraOutlineDark = Color(0xFF6F8A81)
val AuraOutlineVariantDark = Color(0xFF2E463F)

val AuraSurfaceContainerLowestDark = Color(0xFF0B1613)
val AuraSurfaceContainerLowDark = Color(0xFF182621)
val AuraSurfaceContainerDark = Color(0xFF1D2C27)
val AuraSurfaceContainerHighDark = Color(0xFF233530)
val AuraSurfaceContainerHighestDark = Color(0xFF2B403A)

// Deep ink used as the base of gradient hero cards. Not a theme role —
// exposed as a compile-time constant so screens can paint gradients that
// read the same in both light and dark schemes.
val AuraInk = Color(0xFF0A2F28)

// ──────────────────────────────────────────────────────────────────────
// Severity scale (1 = mildest mint, 10 = deepest coral).
// Used by the severity edge on log cards, the slider gradient track,
// chart dots and the detail-hero background gradient. Deliberately kept
// within brand — this is not a generic red/green traffic light; the
// cold end lives in the primary family so "mild" still feels calm.
// ──────────────────────────────────────────────────────────────────────
val AuraSeverity1 = Color(0xFF7FD3BF)
val AuraSeverity2 = Color(0xFF8FD4AE)
val AuraSeverity3 = Color(0xFFB5D79C)
val AuraSeverity4 = Color(0xFFD7D38A)
val AuraSeverity5 = Color(0xFFF0C37C)
val AuraSeverity6 = Color(0xFFF5A36E)
val AuraSeverity7 = Color(0xFFF08864)
val AuraSeverity8 = Color(0xFFE56F5C)
val AuraSeverity9 = Color(0xFFD45854)
val AuraSeverity10 = Color(0xFFB3434B)

val AuraSeverityScale: List<Color> = listOf(
    AuraSeverity1, AuraSeverity2, AuraSeverity3, AuraSeverity4, AuraSeverity5,
    AuraSeverity6, AuraSeverity7, AuraSeverity8, AuraSeverity9, AuraSeverity10,
)

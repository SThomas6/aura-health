package com.example.mob_dev_portfolio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.mob_dev_portfolio.data.preferences.ThemeMode

private val LightScheme = lightColorScheme(
    primary = AuraPrimary,
    onPrimary = AuraOnPrimary,
    primaryContainer = AuraPrimaryContainer,
    onPrimaryContainer = AuraOnPrimaryContainer,
    secondary = AuraSecondary,
    onSecondary = AuraOnSecondary,
    secondaryContainer = AuraSecondaryContainer,
    onSecondaryContainer = AuraOnSecondaryContainer,
    tertiary = AuraTertiary,
    onTertiary = AuraOnTertiary,
    tertiaryContainer = AuraTertiaryContainer,
    onTertiaryContainer = AuraOnTertiaryContainer,
    error = AuraError,
    onError = AuraOnError,
    errorContainer = AuraErrorContainer,
    onErrorContainer = AuraOnErrorContainer,
    background = AuraBackground,
    onBackground = AuraOnBackground,
    surface = AuraSurface,
    onSurface = AuraOnSurface,
    surfaceVariant = AuraSurfaceVariant,
    onSurfaceVariant = AuraOnSurfaceVariant,
    outline = AuraOutline,
    outlineVariant = AuraOutlineVariant,
    surfaceContainerLowest = AuraSurfaceContainerLowest,
    surfaceContainerLow = AuraSurfaceContainerLow,
    surfaceContainer = AuraSurfaceContainer,
    surfaceContainerHigh = AuraSurfaceContainerHigh,
    surfaceContainerHighest = AuraSurfaceContainerHighest,
)

private val DarkScheme = darkColorScheme(
    primary = AuraPrimaryDark,
    onPrimary = AuraOnPrimaryDark,
    primaryContainer = AuraPrimaryContainerDark,
    onPrimaryContainer = AuraOnPrimaryContainerDark,
    secondary = AuraSecondaryDark,
    onSecondary = AuraOnSecondaryDark,
    secondaryContainer = AuraSecondaryContainerDark,
    onSecondaryContainer = AuraOnSecondaryContainerDark,
    tertiary = AuraTertiaryDark,
    onTertiary = AuraOnTertiaryDark,
    tertiaryContainer = AuraTertiaryContainerDark,
    onTertiaryContainer = AuraOnTertiaryContainerDark,
    error = AuraErrorDark,
    onError = AuraOnErrorDark,
    errorContainer = AuraErrorContainerDark,
    onErrorContainer = AuraOnErrorContainerDark,
    background = AuraBackgroundDark,
    onBackground = AuraOnBackgroundDark,
    surface = AuraSurfaceDark,
    onSurface = AuraOnSurfaceDark,
    surfaceVariant = AuraSurfaceVariantDark,
    onSurfaceVariant = AuraOnSurfaceVariantDark,
    outline = AuraOutlineDark,
    outlineVariant = AuraOutlineVariantDark,
    surfaceContainerLowest = AuraSurfaceContainerLowestDark,
    surfaceContainerLow = AuraSurfaceContainerLowDark,
    surfaceContainer = AuraSurfaceContainerDark,
    surfaceContainerHigh = AuraSurfaceContainerHighDark,
    surfaceContainerHighest = AuraSurfaceContainerHighestDark,
)

/**
 * Applies the Aura mint-clinical theme.
 *
 * `dynamicColor` defaults to **off** so the designed palette is what ships
 * by default. Users who prefer the Android 12+ wallpaper-tinted look can
 * still opt in from a future settings entry — the plumbing is preserved.
 */
@Composable
fun AuraTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // Material You dynamic colour requires API 31+ for the dynamic*ColorScheme
    // helpers. minSdk on this module is 31 so the SDK_INT guard the lint
    // flagged was dead — `dynamicColor` is the only meaningful gate now.
    val scheme = when {
        dynamicColor -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AuraTypography,
        shapes = AuraShapes,
        content = content,
    )
}

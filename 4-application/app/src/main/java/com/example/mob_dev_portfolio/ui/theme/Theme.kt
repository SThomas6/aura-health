package com.example.mob_dev_portfolio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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
    error = AuraError,
    onError = AuraOnError,
    errorContainer = AuraErrorContainer,
    onErrorContainer = AuraOnErrorContainer,
    background = AuraBackgroundDark,
    onBackground = AuraOnBackgroundDark,
    surface = AuraSurfaceDark,
    onSurface = AuraOnSurfaceDark,
    surfaceVariant = AuraSurfaceVariantDark,
    onSurfaceVariant = AuraOnSurfaceVariantDark,
)

@Composable
fun AuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AuraTypography,
        content = content,
    )
}

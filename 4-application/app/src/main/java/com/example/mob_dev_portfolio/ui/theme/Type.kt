package com.example.mob_dev_portfolio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.example.mob_dev_portfolio.R

// ──────────────────────────────────────────────────────────────────────
// Typography — Plus Jakarta Sans for UI, JetBrains Mono for numerics.
//
// Both families are pulled at runtime through the Google Fonts
// downloadable-fonts provider (see `res/values/font_certs.xml`). That
// avoids shipping ~400 KB of TTFs inside the APK and keeps the install
// size flat while still giving us the redesign's intended typography.
// The provider falls back to the system default if the download fails,
// so first-launch without network still renders — just in the platform
// font, not in Jakarta/Mono.
// ──────────────────────────────────────────────────────────────────────

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val JakartaGoogleFont = GoogleFont("Plus Jakarta Sans")
private val MonoGoogleFont = GoogleFont("JetBrains Mono")

val AuraJakartaFamily: FontFamily = FontFamily(
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold),
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.ExtraBold),
    Font(googleFont = JakartaGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal, style = FontStyle.Italic),
)

val AuraMonoFamily: FontFamily = FontFamily(
    Font(googleFont = MonoGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = MonoGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
    Font(googleFont = MonoGoogleFont, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
)

val AuraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Labels lean monospace for the "medical instrument" feel — used for
    // eyebrows, metric captions and row timestamps throughout the app.
    labelLarge = TextStyle(
        fontFamily = AuraJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AuraMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AuraMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
    ),
)

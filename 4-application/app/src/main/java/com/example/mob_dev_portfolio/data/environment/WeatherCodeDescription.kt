package com.example.mob_dev_portfolio.data.environment

/**
 * WMO weather interpretation codes used by Open-Meteo.
 * Reference: https://open-meteo.com/en/docs#weathervariables
 *
 * Resolved once at save time and persisted — we never re-derive on UI bind,
 * so tweaking this table later won't rewrite history.
 */
internal fun describeWeatherCode(code: Int?): String? = when (code) {
    null -> null
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snowfall"
    77 -> "Snow grains"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    else -> "Unknown ($code)"
}

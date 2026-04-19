package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.environment.describeWeatherCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the WMO → human-readable mapping. The code runs once at save time and
 * the resulting string is persisted, so if we ever tweak the lookup table
 * these tests ensure we only affect future logs, never rewrite history.
 */
class WeatherCodeDescriptionTest {

    @Test
    fun null_code_returns_null() {
        assertNull(describeWeatherCode(null))
    }

    @Test
    fun clear_sky_is_code_zero() {
        assertEquals("Clear sky", describeWeatherCode(0))
    }

    @Test
    fun cloud_cover_codes_map_sensibly() {
        assertEquals("Mainly clear", describeWeatherCode(1))
        assertEquals("Partly cloudy", describeWeatherCode(2))
        assertEquals("Overcast", describeWeatherCode(3))
    }

    @Test
    fun rain_buckets_collapse_to_single_description() {
        // 61/63/65 all mean "Rain" at different intensities. The detail is
        // in the temperature/humidity fields, so one label is fine here.
        assertEquals("Rain", describeWeatherCode(61))
        assertEquals("Rain", describeWeatherCode(63))
        assertEquals("Rain", describeWeatherCode(65))
    }

    @Test
    fun thunderstorm_with_hail_is_distinct_from_plain_thunderstorm() {
        assertEquals("Thunderstorm", describeWeatherCode(95))
        assertEquals("Thunderstorm with hail", describeWeatherCode(96))
        assertEquals("Thunderstorm with hail", describeWeatherCode(99))
    }

    @Test
    fun snow_codes_cover_both_steady_and_shower() {
        assertEquals("Snowfall", describeWeatherCode(71))
        assertEquals("Snow showers", describeWeatherCode(85))
    }

    @Test
    fun unknown_code_surfaces_the_raw_value_for_debuggability() {
        // If Open-Meteo ever ships a new WMO code before we update the
        // mapping, we want the raw number in the persisted string so the
        // user's log is still informative instead of an opaque "Unknown".
        assertEquals("Unknown (42)", describeWeatherCode(42))
    }
}

package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder.AddressParts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the display-string fallback chain for the human-readable location
 * feature. The real [com.example.mob_dev_portfolio.data.location.AndroidGeocoder]
 * delegates to [ReverseGeocoder.format], so covering the formatter here is
 * equivalent to covering the success + fallback branches of the real impl,
 * minus the platform Geocoder call.
 */
class ReverseGeocoderFormatTest {

    @Test
    fun happy_path_combines_locality_and_admin_area() {
        val parts = AddressParts(locality = "Cardiff", adminArea = "Wales")
        assertEquals("Cardiff, Wales", ReverseGeocoder.format(parts))
    }

    @Test
    fun null_locality_falls_back_to_sub_admin_area() {
        val parts = AddressParts(
            locality = null,
            adminArea = "Greater London",
            subAdminArea = "Camden",
            countryName = "United Kingdom",
        )
        assertEquals("Camden", ReverseGeocoder.format(parts))
    }

    @Test
    fun null_locality_and_null_sub_admin_falls_back_to_country() {
        val parts = AddressParts(
            locality = null,
            adminArea = null,
            subAdminArea = null,
            countryName = "United Kingdom",
        )
        assertEquals("United Kingdom", ReverseGeocoder.format(parts))
    }

    @Test
    fun blank_locality_is_treated_as_missing() {
        val parts = AddressParts(
            locality = "   ",
            adminArea = "Wales",
            subAdminArea = "Cardiff County",
        )
        assertEquals("Cardiff County", ReverseGeocoder.format(parts))
    }

    @Test
    fun all_null_fields_returns_unavailable_string() {
        assertEquals(
            ReverseGeocoder.UNAVAILABLE,
            ReverseGeocoder.format(AddressParts()),
        )
    }

    @Test
    fun null_parts_returns_unavailable_string() {
        assertEquals(ReverseGeocoder.UNAVAILABLE, ReverseGeocoder.format(null))
    }

    @Test
    fun only_locality_is_better_than_unavailable() {
        val parts = AddressParts(locality = "Cardiff")
        assertEquals("Cardiff", ReverseGeocoder.format(parts))
    }

    @Test
    fun only_admin_area_is_used_when_locality_missing() {
        val parts = AddressParts(adminArea = "Wales")
        assertEquals("Wales", ReverseGeocoder.format(parts))
    }

    @Test
    fun unavailable_constant_matches_spec() {
        // The acceptance criteria explicitly calls for a "Location unavailable"
        // fallback string — lock that wording in.
        assertEquals("Location unavailable", ReverseGeocoder.UNAVAILABLE)
    }
}

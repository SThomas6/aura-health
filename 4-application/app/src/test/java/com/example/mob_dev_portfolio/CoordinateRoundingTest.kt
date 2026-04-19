package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.location.CoordinateRounding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Privacy requirement: coordinates hitting the database must be rounded to
 * two decimal places (~1.1 km precision) *before* insert. These tests pin the
 * rounding semantics so a future refactor can't accidentally widen the grid.
 */
class CoordinateRoundingTest {

    private val delta = 1e-9

    @Test
    fun rounds_to_two_decimal_places_half_up() {
        assertEquals(51.51, CoordinateRounding.roundCoordinate(51.50741), delta)
        assertEquals(-0.13, CoordinateRounding.roundCoordinate(-0.12765), delta)
    }

    @Test
    fun half_up_boundary_reflects_ieee754_representation() {
        // BigDecimal(1.005) captures the *exact* binary representation of the
        // double, which is 1.0049999999999... — so HALF_UP rounds it DOWN to
        // 1.00. This is the expected, deterministic behaviour of the
        // BigDecimal(Double) constructor and we lock it in here so a future
        // switch to BigDecimal(String) doesn't silently change outputs.
        assertEquals(1.00, CoordinateRounding.roundCoordinate(1.005), delta)
        // A value whose binary representation is truly ≥ .xx5 rounds up:
        assertEquals(1.02, CoordinateRounding.roundCoordinate(1.015001), delta)
    }

    @Test
    fun already_two_decimal_inputs_are_unchanged() {
        assertEquals(40.71, CoordinateRounding.roundCoordinate(40.71), delta)
        assertEquals(-74.01, CoordinateRounding.roundCoordinate(-74.01), delta)
    }

    @Test
    fun extreme_coordinates_stay_within_valid_ranges() {
        assertEquals(90.00, CoordinateRounding.roundCoordinate(89.999999), delta)
        assertEquals(-90.00, CoordinateRounding.roundCoordinate(-89.999999), delta)
        assertEquals(180.00, CoordinateRounding.roundCoordinate(179.999999), delta)
        assertEquals(-180.00, CoordinateRounding.roundCoordinate(-179.999999), delta)
    }

    @Test
    fun zero_is_exact() {
        assertEquals(0.0, CoordinateRounding.roundCoordinate(0.0), delta)
    }

    @Test
    fun null_input_returns_null() {
        assertNull(CoordinateRounding.roundOrNull(null))
    }

    @Test
    fun non_null_input_is_rounded() {
        assertEquals(12.35, CoordinateRounding.roundOrNull(12.34567)!!, delta)
    }

    @Test
    fun rounding_throws_away_precision_below_100_meters() {
        // 51.507400 and 51.509999 both map to 51.51 at the 2dp grid — this
        // is the privacy property we rely on: the DB never sees full GPS
        // accuracy.
        val a = CoordinateRounding.roundCoordinate(51.507400)
        val b = CoordinateRounding.roundCoordinate(51.509999)
        assertEquals(a, b, delta)
    }
}

package com.example.mob_dev_portfolio.data.location

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Centralised lat/lon precision policy. Two decimal places ≈ 1.1 km on the
 * ground at the equator — granular enough for "what was the weather here?"
 * but coarse enough to satisfy the privacy NFR: we never persist
 * full-accuracy GPS, even briefly.
 *
 * Rounding goes through [BigDecimal] HALF_UP rather than the more obvious
 * `(v * 100).toInt() / 100.0` trick to dodge the IEEE-754 binary-rounding
 * surprises you get on negative or near-boundary doubles, which would
 * otherwise occasionally yield off-by-one displays in tests.
 *
 * Every save path (symptom logs, environmental fetch, reverse geocoding)
 * MUST funnel through this object — that's the contract that lets
 * [ReverseGeocoder] honestly document "we never see full-accuracy GPS".
 */
object CoordinateRounding {
    /** ~1.1 km horizontal precision at the equator — fine for weather, coarse for privacy. */
    private const val DECIMAL_PLACES: Int = 2

    /** Round a coordinate to [DECIMAL_PLACES] using HALF_UP — see object KDoc for rationale. */
    fun roundCoordinate(value: Double): Double =
        BigDecimal(value).setScale(DECIMAL_PLACES, RoundingMode.HALF_UP).toDouble()

    /** Convenience for nullable inputs — keeps the call site free of `?.let { round... }`. */
    fun roundOrNull(value: Double?): Double? = value?.let(::roundCoordinate)
}

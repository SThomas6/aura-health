package com.example.mob_dev_portfolio.data.location

import java.math.BigDecimal
import java.math.RoundingMode

object CoordinateRounding {
    const val DECIMAL_PLACES: Int = 2

    fun roundCoordinate(value: Double): Double =
        BigDecimal(value).setScale(DECIMAL_PLACES, RoundingMode.HALF_UP).toDouble()

    fun roundOrNull(value: Double?): Double? = value?.let(::roundCoordinate)
}

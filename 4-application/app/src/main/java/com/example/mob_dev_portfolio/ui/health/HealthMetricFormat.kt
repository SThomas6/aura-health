package com.example.mob_dev_portfolio.ui.health

import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import java.util.Locale

/**
 * Metric-aware value formatting shared by the Home mini-card headline
 * and the fullscreen detail screen. Keeps all the "steps get no decimals,
 * weight gets one, SpO₂ gets a % suffix" logic in one place so the two
 * surfaces stay visually consistent.
 */
object HealthMetricFormat {

    /** The short unit suffix rendered after the headline number. */
    fun unitLabel(metric: HealthConnectMetric): String = when (metric) {
        HealthConnectMetric.Steps -> "steps"
        HealthConnectMetric.ActiveCaloriesBurned -> "kcal"
        HealthConnectMetric.ExerciseSession -> "sessions"
        HealthConnectMetric.HeartRate,
        HealthConnectMetric.RestingHeartRate -> "bpm"
        HealthConnectMetric.OxygenSaturation -> "%"
        HealthConnectMetric.RespiratoryRate -> "rpm"
        HealthConnectMetric.SleepSession -> "h"
        HealthConnectMetric.Weight -> "kg"
        HealthConnectMetric.Height -> "m"
        HealthConnectMetric.BodyFat -> "%"
    }

    /**
     * Format a raw bucket value for display. Sleep is stored as minutes
     * so we convert to hours here to match [unitLabel]. Steps always
     * render as whole integers; decimals everywhere else.
     */
    fun formatValue(metric: HealthConnectMetric, value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        return when (metric) {
            HealthConnectMetric.Steps,
            HealthConnectMetric.ExerciseSession -> String.format(Locale.getDefault(), "%,d", value.toLong())
            HealthConnectMetric.ActiveCaloriesBurned -> String.format(Locale.getDefault(), "%,d", value.toLong())
            HealthConnectMetric.HeartRate,
            HealthConnectMetric.RestingHeartRate -> String.format(Locale.getDefault(), "%d", value.toLong())
            HealthConnectMetric.OxygenSaturation -> String.format(Locale.getDefault(), "%.1f", value)
            HealthConnectMetric.RespiratoryRate -> String.format(Locale.getDefault(), "%.1f", value)
            HealthConnectMetric.SleepSession -> String.format(Locale.getDefault(), "%.1f", value / 60.0)
            HealthConnectMetric.Weight -> String.format(Locale.getDefault(), "%.1f", value)
            HealthConnectMetric.Height -> String.format(Locale.getDefault(), "%.2f", value)
            HealthConnectMetric.BodyFat -> String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    /**
     * Which [com.example.mob_dev_portfolio.data.health.HealthHistoryRepository.Summary]
     * field is the right "headline number" for this metric. Cumulative
     * metrics want the total; point-in-time metrics want the latest
     * reading.
     */
    fun headline(metric: HealthConnectMetric, summary: com.example.mob_dev_portfolio.data.health.HealthHistoryRepository.Summary): Double? =
        when (metric) {
            HealthConnectMetric.Steps,
            HealthConnectMetric.ActiveCaloriesBurned,
            HealthConnectMetric.ExerciseSession -> summary.total
            HealthConnectMetric.SleepSession -> summary.average
            HealthConnectMetric.HeartRate,
            HealthConnectMetric.RestingHeartRate,
            HealthConnectMetric.OxygenSaturation,
            HealthConnectMetric.RespiratoryRate,
            HealthConnectMetric.Weight,
            HealthConnectMetric.Height,
            HealthConnectMetric.BodyFat -> summary.latest ?: summary.average
        }

    /** Headline caption — "7-day total", "Latest", etc. */
    fun headlineCaption(metric: HealthConnectMetric): String = when (metric) {
        HealthConnectMetric.Steps,
        HealthConnectMetric.ActiveCaloriesBurned,
        HealthConnectMetric.ExerciseSession -> "7-day total"
        HealthConnectMetric.SleepSession -> "Avg per night"
        HealthConnectMetric.HeartRate,
        HealthConnectMetric.RestingHeartRate,
        HealthConnectMetric.OxygenSaturation,
        HealthConnectMetric.RespiratoryRate,
        HealthConnectMetric.Weight,
        HealthConnectMetric.Height,
        HealthConnectMetric.BodyFat -> "Latest"
    }
}

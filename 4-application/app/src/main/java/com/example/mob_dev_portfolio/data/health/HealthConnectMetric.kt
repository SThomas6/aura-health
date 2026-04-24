package com.example.mob_dev_portfolio.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * The catalogue of Health Connect record types this app knows how to
 * consume. Each entry bundles together:
 *
 *  - the Health Connect record class (for SDK `readRecords` + permission
 *    strings),
 *  - a stable [storageKey] used by preferences + analysis snapshots (so
 *    renaming the enum label later doesn't invalidate persisted state),
 *  - a [displayLabel] and [shortLabel] for the settings / results UI,
 *  - a [category] so the settings screen can group them.
 *
 * BMI is not listed here — it's derived on-demand in [HealthSnapshot] from
 * the latest Height + Weight readings, which matches how the Health Connect
 * API exposes the two: there is no standalone BMI record type.
 */
enum class HealthConnectMetric(
    val storageKey: String,
    val displayLabel: String,
    val shortLabel: String,
    val category: HealthMetricCategory,
    val recordClass: KClass<out Record>,
) {
    Steps(
        storageKey = "steps",
        displayLabel = "Steps",
        shortLabel = "Steps",
        category = HealthMetricCategory.Activity,
        recordClass = StepsRecord::class,
    ),
    ActiveCaloriesBurned(
        storageKey = "active_calories_burned",
        displayLabel = "Active calories burned",
        shortLabel = "Active kcal",
        category = HealthMetricCategory.Activity,
        recordClass = ActiveCaloriesBurnedRecord::class,
    ),
    ExerciseSession(
        storageKey = "exercise_session",
        displayLabel = "Exercise sessions",
        shortLabel = "Exercise",
        category = HealthMetricCategory.Activity,
        recordClass = ExerciseSessionRecord::class,
    ),
    HeartRate(
        storageKey = "heart_rate",
        displayLabel = "Heart rate",
        shortLabel = "HR",
        category = HealthMetricCategory.Vitals,
        recordClass = HeartRateRecord::class,
    ),
    RestingHeartRate(
        storageKey = "resting_heart_rate",
        displayLabel = "Resting heart rate",
        shortLabel = "Resting HR",
        category = HealthMetricCategory.Vitals,
        recordClass = RestingHeartRateRecord::class,
    ),
    OxygenSaturation(
        storageKey = "oxygen_saturation",
        displayLabel = "Oxygen saturation",
        shortLabel = "SpO₂",
        category = HealthMetricCategory.Vitals,
        recordClass = OxygenSaturationRecord::class,
    ),
    RespiratoryRate(
        storageKey = "respiratory_rate",
        displayLabel = "Respiratory rate",
        shortLabel = "Resp rate",
        category = HealthMetricCategory.Vitals,
        recordClass = RespiratoryRateRecord::class,
    ),
    SleepSession(
        storageKey = "sleep_session",
        displayLabel = "Sleep",
        shortLabel = "Sleep",
        category = HealthMetricCategory.Sleep,
        recordClass = SleepSessionRecord::class,
    ),
    Height(
        storageKey = "height",
        displayLabel = "Height",
        shortLabel = "Height",
        category = HealthMetricCategory.Body,
        recordClass = HeightRecord::class,
    ),
    Weight(
        storageKey = "weight",
        displayLabel = "Weight",
        shortLabel = "Weight",
        category = HealthMetricCategory.Body,
        recordClass = WeightRecord::class,
    ),
    BodyFat(
        storageKey = "body_fat",
        displayLabel = "Body fat %",
        shortLabel = "Body fat %",
        category = HealthMetricCategory.Body,
        recordClass = BodyFatRecord::class,
    );

    /** The exact permission string Health Connect expects for a read grant. */
    val readPermission: String get() = HealthPermission.getReadPermission(recordClass)

    companion object {
        /** Convenience lookup by [storageKey] — used by preferences. */
        fun fromStorageKey(key: String): HealthConnectMetric? =
            entries.firstOrNull { it.storageKey == key }

        /**
         * Default-on set for a fresh install. Deliberately conservative:
         * we enable the metrics the Gemini prompt benefits from most
         * (activity + sleep + resting HR + body composition for BMI) and
         * leave noisy ones (continuous HR, exercise sessions) off so
         * first-time users aren't forced to grant a dozen permissions.
         */
        val DefaultEnabled: Set<HealthConnectMetric> = setOf(
            Steps,
            RestingHeartRate,
            SleepSession,
            Height,
            Weight,
        )
    }
}

/** Broad buckets used to group metric toggles in the settings UI. */
enum class HealthMetricCategory(val displayLabel: String) {
    Activity(displayLabel = "Activity"),
    Vitals(displayLabel = "Vitals"),
    Sleep(displayLabel = "Sleep"),
    Body(displayLabel = "Body measurements"),
}

package com.example.mob_dev_portfolio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity backing the `symptom_logs` table — the canonical
 * persistence form of one user-recorded symptom episode.
 *
 * The matching domain type is [SymptomLog]; mappers in
 * [SymptomLogRepository] translate between the two so the UI never
 * touches Room types directly. The entity stores [contextTags] as a
 * single serialised `String` (not a Room `@TypeConverter`-driven list)
 * to keep the column simple and the migration story trivial — the
 * format is documented next to the encode/decode helpers in the same
 * file.
 *
 * Most non-core fields are nullable so a save can succeed even when
 * peripheral data (location, weather, AQI) is unavailable. Losing a
 * symptom log because the network was offline would be unacceptable
 * UX, so the persistence path is forgiving.
 */
@Entity(tableName = "symptom_logs")
data class SymptomLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symptomName: String,
    val description: String,
    val startEpochMillis: Long,
    /** `null` means the symptom is still ongoing — Home/History sort it under "Ongoing". */
    val endEpochMillis: Long?,
    val severity: Int,
    val medication: String,
    /** Serialised tag list. See `encodeContextTags` / `decodeContextTags` for the format. */
    val contextTags: String,
    val notes: String,
    val createdAtEpochMillis: Long,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationName: String? = null,
    // Environmental metrics — captured once at save time, never re-fetched.
    // Every column is nullable so a save succeeds even when the API fails,
    // the network is offline, or the user opted out of location.
    val weatherCode: Int? = null,
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    val airQualityIndex: Int? = null,
)

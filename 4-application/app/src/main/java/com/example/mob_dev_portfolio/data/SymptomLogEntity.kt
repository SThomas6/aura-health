package com.example.mob_dev_portfolio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "symptom_logs")
data class SymptomLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symptomName: String,
    val description: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val severity: Int,
    val medication: String,
    val contextTags: String,
    val notes: String,
    val createdAtEpochMillis: Long,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationName: String? = null,
)

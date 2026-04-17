package com.example.mob_dev_portfolio.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

open class SymptomLogRepository(private val dao: SymptomLogDao) {

    open fun observeAll(): Flow<List<SymptomLog>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    open fun observeCount(): Flow<Int> = dao.observeCount()

    open suspend fun save(log: SymptomLog): Long = dao.insert(log.toEntity())

    open suspend fun delete(id: Long) = dao.delete(id)
}

data class SymptomLog(
    val id: Long = 0L,
    val symptomName: String,
    val description: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val severity: Int,
    val medication: String,
    val contextTags: List<String>,
    val notes: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

private fun SymptomLog.toEntity() = SymptomLogEntity(
    id = id,
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = contextTags.joinToString("|"),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun SymptomLogEntity.toDomain() = SymptomLog(
    id = id,
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = if (contextTags.isBlank()) emptyList() else contextTags.split("|"),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
)

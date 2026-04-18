package com.example.mob_dev_portfolio.data

import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import com.example.mob_dev_portfolio.ui.log.LogValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

open class SymptomLogRepository(private val dao: SymptomLogDao) {

    open fun observeAll(): Flow<List<SymptomLog>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    open fun observeFiltered(filter: HistoryFilter): Flow<List<SymptomLog>> {
        val trimmedQuery = filter.query.trim().takeIf { it.isNotEmpty() }
        val minSeverity = filter.minSeverity.coerceIn(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY)
        val maxSeverity = filter.maxSeverity.coerceIn(minSeverity, LogValidator.MAX_SEVERITY)
        val sortKey = filter.sort.daoKey
        return dao.observeFiltered(
            query = trimmedQuery,
            minSeverity = minSeverity,
            maxSeverity = maxSeverity,
            startAfter = filter.startAfterEpochMillis,
            startBefore = filter.startBeforeEpochMillis,
            sortKey = sortKey,
        ).map { list ->
            val domain = list.map { it.toDomain() }
            if (filter.tags.isEmpty()) domain
            else domain.filter { log -> filter.tags.all { tag -> log.contextTags.contains(tag) } }
        }
    }

    open fun observeById(id: Long): Flow<SymptomLog?> =
        dao.observeById(id).map { it?.toDomain() }

    open fun observeCount(): Flow<Int> = dao.observeCount()

    open suspend fun save(log: SymptomLog): Long = dao.insert(log.toEntity())

    open suspend fun update(log: SymptomLog): Int = dao.update(log.toEntity())

    open suspend fun delete(id: Long) = dao.delete(id)
}

private val HistorySort.daoKey: String
    get() = when (this) {
        HistorySort.DateDesc -> "DATE_DESC"
        HistorySort.DateAsc -> "DATE_ASC"
        HistorySort.SeverityDesc -> "SEVERITY_DESC"
        HistorySort.SeverityAsc -> "SEVERITY_ASC"
        HistorySort.NameAsc -> "NAME_ASC"
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

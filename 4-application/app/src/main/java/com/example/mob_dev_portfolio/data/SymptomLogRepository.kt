package com.example.mob_dev_portfolio.data

import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import com.example.mob_dev_portfolio.ui.history.HistoryFilter
import com.example.mob_dev_portfolio.ui.history.HistorySort
import com.example.mob_dev_portfolio.ui.log.LogValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

open class SymptomLogRepository(
    private val dao: SymptomLogDao,
    /**
     * Optional sibling repository for photo attachments. Injected
     * (rather than newed-up here) so tests can exercise the log
     * repository in isolation, and nullable so older test doubles
     * that don't care about photos keep compiling. Production
     * construction in [com.example.mob_dev_portfolio.DefaultAppContainer]
     * always supplies a real instance.
     *
     * When non-null, [delete] cascades through this repository
     * first so the encrypted photo files on disk disappear before
     * the FK cascade drops the DB rows — NFR-PA-05 "photos must
     * be deleted when the parent log is removed".
     */
    private val photoRepository: SymptomPhotoRepository? = null,
) {

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

    open suspend fun delete(id: Long) {
        // Photo files first: once the row is gone the FK cascade drops
        // the photo rows and we lose the handles we'd need to find the
        // files on disk. The photo-repo's own `deleteForLog` walks the
        // rows and unlinks each file before dropping the rows itself,
        // so the order here keeps the file-system and the DB in sync.
        photoRepository?.deleteForLog(id)
        dao.delete(id)
    }
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
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    /**
     * Human-readable place string persisted once at save time. Never derived on
     * read — the DB row is the source of truth. Null for logs captured before
     * the geocoding migration or when geocoding failed.
     */
    val locationName: String? = null,
    /**
     * Environmental metrics captured once at save time from Open-Meteo.
     * Every field is nullable so the symptom row persists even when the
     * API call times out, fails, or the device is offline. On edit these
     * values are carried through unchanged — the network layer is never
     * re-consulted for an existing log.
     */
    val weatherCode: Int? = null,
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    val airQualityIndex: Int? = null,
)

private fun SymptomLog.toEntity() = SymptomLogEntity(
    id = id,
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = encodeContextTags(contextTags),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
    locationLatitude = locationLatitude,
    locationLongitude = locationLongitude,
    locationName = locationName,
    weatherCode = weatherCode,
    weatherDescription = weatherDescription,
    temperatureCelsius = temperatureCelsius,
    humidityPercent = humidityPercent,
    pressureHpa = pressureHpa,
    airQualityIndex = airQualityIndex,
)

private fun SymptomLogEntity.toDomain() = SymptomLog(
    id = id,
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = decodeContextTags(contextTags),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
    locationLatitude = locationLatitude,
    locationLongitude = locationLongitude,
    locationName = locationName,
    weatherCode = weatherCode,
    weatherDescription = weatherDescription,
    temperatureCelsius = temperatureCelsius,
    humidityPercent = humidityPercent,
    pressureHpa = pressureHpa,
    airQualityIndex = airQualityIndex,
)

// ── Context-tag serialization ─────────────────────────────────────────
//
// The original format was a pipe-joined string ("fatigue|stress"), which
// silently corrupts any tag value containing a `|`. The catalog never
// shipped one, but the same column is also written by AI summary
// suggestions and by future user-defined tags, so the format is now
// JSON.
//
// Reads accept either format: anything starting with `[` is parsed as a
// JSON array; anything else is treated as legacy pipe-delimited and is
// rewritten on the next save. No explicit migration is needed because
// every read passes through this decoder.

private val contextTagsJson = Json { ignoreUnknownKeys = true }
private val contextTagsSerializer = ListSerializer(String.serializer())

internal fun encodeContextTags(tags: List<String>): String =
    if (tags.isEmpty()) "" else contextTagsJson.encodeToString(contextTagsSerializer, tags)

internal fun decodeContextTags(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return if (raw.startsWith("[")) {
        runCatching { contextTagsJson.decodeFromString(contextTagsSerializer, raw) }
            .getOrElse { raw.split("|").filter { it.isNotEmpty() } }
    } else {
        raw.split("|").filter { it.isNotEmpty() }
    }
}

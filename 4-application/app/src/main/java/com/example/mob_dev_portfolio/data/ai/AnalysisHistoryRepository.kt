package com.example.mob_dev_portfolio.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain-friendly wrapper around [AnalysisRunDao].
 *
 * The repository maps the Room row into the richer [AnalysisRun] model so
 * the UI doesn't have to touch Room-only types (or deal with the string
 * form of the guidance enum). Everything read-shaped is a Flow so Compose
 * binds reactively — the history list updates the moment a worker commits
 * a new row.
 *
 * Exposed as `open` so unit tests can supply a fake without dragging
 * Room into the test classpath.
 */
open class AnalysisHistoryRepository(
    private val dao: AnalysisRunDao,
) {

    /** All past runs, newest first, mapped to the domain model. */
    open val runs: Flow<List<AnalysisRun>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    /** A single run plus its linked log ids, or null when the row has been deleted. */
    open fun observeRun(id: Long): Flow<AnalysisRun?> =
        dao.observeById(id).map { it?.toDomain() }

    /** The symptom log ids that fed a given run. */
    open fun observeLogIdsFor(runId: Long): Flow<List<Long>> =
        dao.observeLogIdsFor(runId)

    /**
     * Insert a fresh run. Returns the generated rowId so callers can
     * stash it somewhere (e.g. to construct a deep-link that lands on
     * the specific run's detail view).
     */
    open suspend fun recordRun(
        summaryText: String,
        guidance: AnalysisGuidance,
        completedAtEpochMillis: Long,
        logIds: List<Long>,
    ): Long = dao.insertRunWithLogs(
        run = AnalysisRunEntity(
            completedAtEpochMillis = completedAtEpochMillis,
            guidanceName = guidance.name,
            headline = guidance.headline,
            summaryText = summaryText,
        ),
        logIds = logIds,
    )

    open suspend fun delete(id: Long) {
        dao.delete(id)
    }
}

/**
 * UI-facing model for a single past analysis run.
 *
 * Kept separate from [AnalysisRunEntity] so the storage schema can evolve
 * (nullable columns, renames, new indices) without the UI recompiling.
 * The guidance is returned as the richer [AnalysisGuidance] enum — a row
 * written on an older app version with an unknown value falls back to
 * [AnalysisGuidance.Clear] so the screen still renders something rather
 * than crashing.
 */
data class AnalysisRun(
    val id: Long,
    val completedAtEpochMillis: Long,
    val guidance: AnalysisGuidance,
    val headline: String,
    val summaryText: String,
)

private fun AnalysisRunEntity.toDomain(): AnalysisRun =
    AnalysisRun(
        id = id,
        completedAtEpochMillis = completedAtEpochMillis,
        guidance = runCatching { AnalysisGuidance.valueOf(guidanceName) }
            .getOrDefault(AnalysisGuidance.Clear),
        headline = headline,
        summaryText = summaryText,
    )

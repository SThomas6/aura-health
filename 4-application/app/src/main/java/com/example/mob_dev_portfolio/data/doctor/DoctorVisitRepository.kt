package com.example.mob_dev_portfolio.data.doctor

import androidx.room.withTransaction
import com.example.mob_dev_portfolio.data.AuraDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// ── Domain models ──────────────────────────────────────────────────────

/** UI-facing twin of [DoctorVisitEntity]. Keeps Room types out of view-models. */
data class DoctorVisit(
    val id: Long,
    val doctorName: String,
    val visitDateEpochMillis: Long,
    val summary: String,
    val createdAtEpochMillis: Long,
)

/**
 * UI-facing twin of [DoctorDiagnosisEntity], with [linkedLogIds]
 * pre-resolved so the detail screen can render the linked-symptom rows
 * without a follow-up query per diagnosis.
 */
data class DoctorDiagnosis(
    val id: Long,
    val visitId: Long,
    val label: String,
    val notes: String,
    val createdAtEpochMillis: Long,
    val linkedLogIds: List<Long> = emptyList(),
)

/** Aggregate for the visit-detail screen. */
data class DoctorVisitDetail(
    val visit: DoctorVisit,
    val coveredLogIds: List<Long>,
    val diagnoses: List<DoctorDiagnosis>,
)

/** Editor form payload. */
data class DoctorVisitDraft(
    val id: Long = 0L,
    val doctorName: String,
    val visitDateEpochMillis: Long,
    val summary: String,
    val coveredLogIds: Set<Long>,
    val diagnoses: List<DoctorDiagnosisDraft>,
)

data class DoctorDiagnosisDraft(
    val id: Long = 0L,
    val label: String,
    val notes: String,
    val linkedLogIds: Set<Long>,
)

/**
 * Badge payload for [com.example.mob_dev_portfolio.ui.detail.LogDetailScreen].
 * A given log can be cleared OR linked to a diagnosis. When both states
 * coexist (a UI slip) the clearance wins — most restrictive user intent.
 */
sealed interface LogDoctorAnnotation {
    val visit: DoctorVisit

    data class Cleared(override val visit: DoctorVisit) : LogDoctorAnnotation
    data class LinkedToDiagnosis(
        override val visit: DoctorVisit,
        val diagnosis: DoctorDiagnosis,
    ) : LogDoctorAnnotation
}

/**
 * Point-in-time read that the AI pipeline uses to decide which logs to
 * drop and which to annotate. A plain struct (rather than live Flow)
 * because the analysis worker wants a consistent snapshot for the
 * duration of a single run.
 */
data class DoctorContextSnapshot(
    val clearedLogIds: Set<Long>,
    /** logId → diagnosis label. Populated for every log tied to a diagnosis. */
    val diagnosisLabelByLogId: Map<Long, String>,
    /** diagnosisId → label, for building the "Known diagnoses" section. */
    val diagnosisLabels: Map<Long, String>,
    /** diagnosisId → every log it's linked to. Used to render brief history. */
    val linkedLogIdsByDiagnosis: Map<Long, Set<Long>>,
) {
    companion object {
        val Empty = DoctorContextSnapshot(
            clearedLogIds = emptySet(),
            diagnosisLabelByLogId = emptyMap(),
            diagnosisLabels = emptyMap(),
            linkedLogIdsByDiagnosis = emptyMap(),
        )
    }
}

// ── Repository ─────────────────────────────────────────────────────────

/**
 * Coordinates writes across the four tables that make up the Doctor
 * Visits feature: `doctor_visits`, `doctor_visit_covered_logs`,
 * `doctor_diagnoses`, and `doctor_diagnosis_logs`. The repository's job
 * is to keep those tables consistent — every multi-table mutation
 * (saving a visit, attaching a log to a diagnosis) runs inside a
 * `withTransaction` block so a crash mid-write can never leave a
 * dangling join row.
 *
 * Reads are split between live `Flow`s (for the live UI screens) and
 * one-shot suspending snapshots (for the background AI worker, which
 * needs a stable view for the duration of a single run).
 *
 * `open` so unit tests can subclass with a fake without going through
 * Room.
 */
open class DoctorVisitRepository(
    private val database: AuraDatabase,
    private val visitDao: DoctorVisitDao,
    private val diagnosisDao: DoctorDiagnosisDao,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    open fun observeVisits(): Flow<List<DoctorVisit>> =
        visitDao.observeVisits().map { list -> list.map { it.toDomain() } }

    open fun observeAllDiagnoses(): Flow<List<DoctorDiagnosis>> =
        diagnosisDao.observeAllDiagnoses().map { list -> list.map { it.toDomainWithoutLinks() } }

    /**
     * Full detail for a visit, diagnoses and covered-logs included. The
     * diagnoses list carries [DoctorDiagnosis.linkedLogIds] pre-populated
     * so the detail screen doesn't need a per-row query.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    open fun observeVisitDetail(visitId: Long): Flow<DoctorVisitDetail?> =
        visitDao.observeVisit(visitId).flatMapLatest { entity ->
            if (entity == null) return@flatMapLatest flowOf(null)
            combine(
                visitDao.observeCoveredLogIdsForVisit(visitId),
                diagnosisDao.observeDiagnosesForVisit(visitId),
            ) { coveredIds, diagnoses -> entity to (coveredIds to diagnoses) }
                .flatMapLatest { (visitEntity, pair) ->
                    val (coveredIds, diagnoses) = pair
                    if (diagnoses.isEmpty()) {
                        flowOf(
                            DoctorVisitDetail(
                                visit = visitEntity.toDomain(),
                                coveredLogIds = coveredIds,
                                diagnoses = emptyList(),
                            ),
                        )
                    } else {
                        val linkFlows: List<Flow<Pair<Long, List<Long>>>> =
                            diagnoses.map { d ->
                                diagnosisDao
                                    .observeLogIdsForDiagnosis(d.id)
                                    .map { logIds -> d.id to logIds }
                            }
                        combine(linkFlows) { arr -> arr.toMap() }.map { linksMap ->
                            DoctorVisitDetail(
                                visit = visitEntity.toDomain(),
                                coveredLogIds = coveredIds,
                                diagnoses = diagnoses.map { d ->
                                    d.toDomain(linksMap[d.id] ?: emptyList())
                                },
                            )
                        }
                    }
                }
        }

    /**
     * Badge payload for the log-detail screen. Emits the *most recent*
     * clearance (if any); otherwise the most recent diagnosis link; else
     * null.
     */
    open fun observeLogAnnotation(logId: Long): Flow<LogDoctorAnnotation?> =
        combine(
            visitDao.observeClearingVisitForLog(logId),
            diagnosisDao.observeDiagnosisForLog(logId),
        ) { clearingVisit, diagnosis ->
            when {
                clearingVisit != null -> LogDoctorAnnotation.Cleared(clearingVisit.toDomain())
                diagnosis != null -> {
                    val visitEntity = visitDao.getVisit(diagnosis.visitId)
                        ?: return@combine null
                    LogDoctorAnnotation.LinkedToDiagnosis(
                        visit = visitEntity.toDomain(),
                        diagnosis = diagnosis.toDomainWithoutLinks(),
                    )
                }
                else -> null
            }
        }

    /**
     * Atomically upsert a visit + its covered-logs + its diagnoses. All
     * four tables hit inside a single Room transaction so a crash mid-save
     * can't leave dangling join rows. Returns the visit id.
     */
    open suspend fun saveVisit(draft: DoctorVisitDraft): Long = database.withTransaction {
        val now = nowProvider()
        val visitId = if (draft.id == 0L) {
            visitDao.insertVisit(
                DoctorVisitEntity(
                    doctorName = draft.doctorName.trim(),
                    visitDateEpochMillis = draft.visitDateEpochMillis,
                    summary = draft.summary.trim(),
                    createdAtEpochMillis = now,
                ),
            )
        } else {
            val existing = visitDao.getVisit(draft.id)
            visitDao.updateVisit(
                DoctorVisitEntity(
                    id = draft.id,
                    doctorName = draft.doctorName.trim(),
                    visitDateEpochMillis = draft.visitDateEpochMillis,
                    summary = draft.summary.trim(),
                    // Preserve the original createdAt so sort-by-creation
                    // queries don't bounce rows to the top on edit.
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                ),
            )
            draft.id
        }

        // Covered logs — full replace, because editing the selection is
        // cheaper than diffing and the join table is tiny.
        visitDao.clearCoveredLogsForVisit(visitId)
        draft.coveredLogIds.forEach { logId ->
            visitDao.insertCoveredLog(DoctorVisitCoveredLog(visitId = visitId, logId = logId))
        }

        // Diagnoses — full replace. Deleting orphaned rows cascades to the
        // diagnosis-log join table, so we don't have to clean those up
        // separately for removed diagnoses.
        val existingDiagnoses = diagnosisDao.listDiagnosesForVisit(visitId)
        val incomingIds = draft.diagnoses.mapNotNull { it.id.takeIf { id -> id != 0L } }.toSet()
        existingDiagnoses
            .filterNot { it.id in incomingIds }
            .forEach { diagnosisDao.deleteDiagnosis(it.id) }

        draft.diagnoses.forEach { diag ->
            val diagnosisId = if (diag.id == 0L) {
                diagnosisDao.insertDiagnosis(
                    DoctorDiagnosisEntity(
                        visitId = visitId,
                        label = diag.label.trim(),
                        notes = diag.notes.trim(),
                        createdAtEpochMillis = now,
                    ),
                )
            } else {
                val existing = diagnosisDao.getDiagnosis(diag.id)
                diagnosisDao.updateDiagnosis(
                    DoctorDiagnosisEntity(
                        id = diag.id,
                        visitId = visitId,
                        label = diag.label.trim(),
                        notes = diag.notes.trim(),
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                    ),
                )
                diag.id
            }
            diagnosisDao.clearLogsForDiagnosis(diagnosisId)
            diag.linkedLogIds.forEach { logId ->
                diagnosisDao.insertDiagnosisLog(
                    DoctorDiagnosisLog(diagnosisId = diagnosisId, logId = logId),
                )
            }
        }

        visitId
    }

    open suspend fun deleteVisit(id: Long) = visitDao.deleteVisit(id)

    /** Used by the log-creation flow to optionally pin a new log to an existing diagnosis. */
    open suspend fun attachLogToDiagnosis(logId: Long, diagnosisId: Long) =
        database.withTransaction {
            // Replace any existing link so toggling between diagnoses
            // doesn't leave a log linked to two things at once.
            diagnosisDao.clearDiagnosisLinksForLog(logId)
            diagnosisDao.insertDiagnosisLog(
                DoctorDiagnosisLog(diagnosisId = diagnosisId, logId = logId),
            )
        }

    open suspend fun detachLogFromAllDiagnoses(logId: Long) =
        diagnosisDao.clearDiagnosisLinksForLog(logId)

    /**
     * Point-in-time read that drives the AI pipeline. Kept as a suspending
     * snapshot (rather than a Flow) because the worker needs one stable
     * view for the duration of a single analysis run.
     */
    open suspend fun snapshotForAnalysis(): DoctorContextSnapshot {
        val cleared = visitDao.listAllClearedLogIds().toSet()
        val diagnoses = diagnosisDao.listAllDiagnoses()
        val labels: Map<Long, String> = diagnoses.associate { it.id to it.label }
        val links: List<DoctorDiagnosisLog> = diagnosisDao.listAllDiagnosisLinks()
        val linkedByDiagnosis: Map<Long, Set<Long>> = links
            .groupBy(DoctorDiagnosisLog::diagnosisId)
            .mapValues { (_, rows) -> rows.map(DoctorDiagnosisLog::logId).toSet() }
        val labelByLog: Map<Long, String> = buildMap {
            diagnoses.forEach { d ->
                (linkedByDiagnosis[d.id] ?: emptySet()).forEach { logId ->
                    // If a log is linked to multiple diagnoses (unsupported in
                    // UI but not prevented at the DB level), later writes win.
                    put(logId, d.label)
                }
            }
        }
        return DoctorContextSnapshot(
            clearedLogIds = cleared,
            diagnosisLabelByLogId = labelByLog,
            diagnosisLabels = labels,
            linkedLogIdsByDiagnosis = linkedByDiagnosis,
        )
    }
}

// ── Entity ↔ domain mapping ────────────────────────────────────────────

private fun DoctorVisitEntity.toDomain() = DoctorVisit(
    id = id,
    doctorName = doctorName,
    visitDateEpochMillis = visitDateEpochMillis,
    summary = summary,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun DoctorDiagnosisEntity.toDomain(linkedLogIds: List<Long>) = DoctorDiagnosis(
    id = id,
    visitId = visitId,
    label = label,
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
    linkedLogIds = linkedLogIds,
)

private fun DoctorDiagnosisEntity.toDomainWithoutLinks() = DoctorDiagnosis(
    id = id,
    visitId = visitId,
    label = label,
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
    linkedLogIds = emptyList(),
)

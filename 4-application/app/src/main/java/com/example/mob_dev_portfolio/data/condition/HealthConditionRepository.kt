package com.example.mob_dev_portfolio.data.condition

import androidx.room.withTransaction
import com.example.mob_dev_portfolio.data.AuraDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Domain model — what UI and tests deal in. */
data class HealthCondition(
    val id: Long,
    val name: String,
    val notes: String,
    val createdAtEpochMillis: Long,
)

/**
 * Snapshot consumed by the AI pipeline. Captures user-declared
 * conditions plus the symptom logs they're linked to so the model can
 * reason about already-explained context the user has flagged.
 */
data class UserConditionsSnapshot(
    /** conditionId → user-friendly label. */
    val conditionLabels: Map<Long, String>,
    /** conditionId → set of linked log ids. */
    val linkedLogIdsByCondition: Map<Long, Set<Long>>,
    /** logId → conditionLabel. Inverse projection for fast lookup per log. */
    val conditionLabelByLogId: Map<Long, String>,
) {
    companion object {
        val Empty = UserConditionsSnapshot(
            conditionLabels = emptyMap(),
            linkedLogIdsByCondition = emptyMap(),
            conditionLabelByLogId = emptyMap(),
        )
    }
}

/**
 * Repository for user-declared health conditions and the join with
 * symptom logs.
 *
 * Open class with virtual methods so test doubles can override
 * individual behaviours without re-implementing the whole surface.
 */
open class HealthConditionRepository(
    private val database: AuraDatabase,
    private val dao: HealthConditionDao,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    open fun observeAll(): Flow<List<HealthCondition>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    open fun observeConditionForLog(logId: Long): Flow<HealthCondition?> =
        dao.observeConditionForLog(logId).map { it?.toDomain() }

    open fun observeAllLinks(): Flow<List<HealthConditionLog>> = dao.observeAllLinks()

    open suspend fun upsert(
        id: Long = 0L,
        name: String,
        notes: String = "",
    ): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return 0L
        return if (id == 0L) {
            dao.insertCondition(
                HealthConditionEntity(
                    name = trimmed,
                    notes = notes.trim(),
                    createdAtEpochMillis = nowProvider(),
                ),
            )
        } else {
            val existing = dao.getCondition(id) ?: return 0L
            dao.updateCondition(
                existing.copy(
                    name = trimmed,
                    notes = notes.trim(),
                ),
            )
            id
        }
    }

    open suspend fun delete(id: Long) = dao.deleteCondition(id)

    /**
     * Pin [logId] to [conditionId]. Replaces any prior link in a single
     * transaction so a log lives under at most one condition (the
     * History grouping invariant). Pass `conditionId = null` to
     * unlink completely.
     */
    open suspend fun setLogCondition(logId: Long, conditionId: Long?) =
        database.withTransaction {
            dao.clearLinksForLog(logId)
            if (conditionId != null) {
                dao.insertConditionLog(
                    HealthConditionLog(conditionId = conditionId, logId = logId),
                )
            }
        }

    /** AI-pipeline snapshot — see [UserConditionsSnapshot]. */
    open suspend fun snapshotForAnalysis(): UserConditionsSnapshot {
        val conditions = dao.listAll()
        if (conditions.isEmpty()) return UserConditionsSnapshot.Empty
        val labels = conditions.associate { it.id to it.name }
        val links = dao.listAllLinks()
        val byCondition: Map<Long, Set<Long>> = links
            .groupBy(HealthConditionLog::conditionId)
            .mapValues { (_, rows) -> rows.map(HealthConditionLog::logId).toSet() }
        val byLog: Map<Long, String> = buildMap {
            conditions.forEach { c ->
                (byCondition[c.id] ?: emptySet()).forEach { logId ->
                    // If the same log is somehow linked to multiple
                    // conditions (the UI prevents this, but the schema
                    // doesn't), later iterations win — deterministic
                    // since [conditions] is sorted.
                    put(logId, c.name)
                }
            }
        }
        return UserConditionsSnapshot(
            conditionLabels = labels,
            linkedLogIdsByCondition = byCondition,
            conditionLabelByLogId = byLog,
        )
    }
}

private fun HealthConditionEntity.toDomain() = HealthCondition(
    id = id,
    name = name,
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
)

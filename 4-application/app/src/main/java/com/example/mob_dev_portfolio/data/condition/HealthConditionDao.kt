package com.example.mob_dev_portfolio.data.condition

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthConditionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCondition(entity: HealthConditionEntity): Long

    @Update
    suspend fun updateCondition(entity: HealthConditionEntity): Int

    @Query("DELETE FROM health_conditions WHERE id = :id")
    suspend fun deleteCondition(id: Long)

    /** Live-updating list of every user-declared condition, newest first. */
    @Query("SELECT * FROM health_conditions ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<HealthConditionEntity>>

    /** One-shot snapshot for the AI pipeline. */
    @Query("SELECT * FROM health_conditions ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun listAll(): List<HealthConditionEntity>

    @Query("SELECT * FROM health_conditions WHERE id = :id LIMIT 1")
    suspend fun getCondition(id: Long): HealthConditionEntity?

    // ── Join table ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConditionLog(link: HealthConditionLog)

    @Delete
    suspend fun deleteConditionLog(link: HealthConditionLog)

    @Query("DELETE FROM health_condition_logs WHERE logId = :logId")
    suspend fun clearLinksForLog(logId: Long)

    @Query("DELETE FROM health_condition_logs WHERE conditionId = :conditionId")
    suspend fun clearLinksForCondition(conditionId: Long)

    /**
     * For a given symptom log, the (single) condition it's linked to —
     * the UI lets a log live under at most one condition so the
     * grouping is unambiguous. Returns null if unlinked.
     */
    @Query(
        """
        SELECT c.* FROM health_conditions c
        INNER JOIN health_condition_logs link ON link.conditionId = c.id
        WHERE link.logId = :logId
        ORDER BY c.createdAtEpochMillis DESC, c.id DESC
        LIMIT 1
        """,
    )
    fun observeConditionForLog(logId: Long): Flow<HealthConditionEntity?>

    /** Snapshot used by the History screen to drive section grouping. */
    @Query("SELECT conditionId, logId FROM health_condition_logs")
    fun observeAllLinks(): Flow<List<HealthConditionLog>>

    /** Snapshot used by the AI pipeline. */
    @Query("SELECT conditionId, logId FROM health_condition_logs")
    suspend fun listAllLinks(): List<HealthConditionLog>
}

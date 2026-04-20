package com.example.mob_dev_portfolio.data.report

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the [ReportArchiveEntity] table.
 *
 * The list query is a Flow — the history screen observes it via
 * `collectAsStateWithLifecycle()` so a successful generate + insert
 * pushes a new row into the LazyColumn without any manual refresh, and
 * a successful delete removes it from the UI immediately.
 *
 * Writes are suspending; all of them are invoked from a coroutine on
 * `Dispatchers.IO` alongside filesystem I/O, so running them outside
 * the DAO's default transaction pool is fine.
 */
@Dao
interface ReportArchiveDao {

    /**
     * Persist a new archive. The entity's unique index on
     * `compressedFileName` guards against accidental double-inserts —
     * ABORT is the safest default: a collision would mean something's
     * wrong upstream (the generator's timestamp-to-ms filename should
     * never collide) and we'd rather surface it than silently clobber
     * an existing row.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ReportArchiveEntity): Long

    /**
     * Newest-first — matches the acceptance criterion "sorted by date
     * and time (newest to oldest)". We sort on `generatedAtEpochMillis`
     * rather than the autoincrement id to stay correct if the clock is
     * ever adjusted between inserts.
     */
    @Query("SELECT * FROM report_archives ORDER BY generatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ReportArchiveEntity>>

    /** Single-row lookup for Open / Share flows. */
    @Query("SELECT * FROM report_archives WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ReportArchiveEntity?

    @Query("DELETE FROM report_archives WHERE id = :id")
    suspend fun delete(id: Long)
}

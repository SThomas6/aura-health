package com.example.mob_dev_portfolio.data.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Thin coordinator between the [ReportArchiveDao] (Room row) and the
 * [HealthReportPdfGenerator] (bytes on disk).
 *
 * The two form a classic dual-write pair, so ordering matters:
 *
 * * **Create**: the generator materialises the compressed file first;
 *   only then do we [register] a row. If the DB insert fails we'd
 *   rather have an orphan file (reclaimable at next generation) than
 *   a row pointing at nothing.
 * * **Delete**: delete the file first, then the row ([delete] below).
 *   If file deletion fails the row stays, the history entry stays
 *   visible, and the user can retry. The reverse order would risk a
 *   missing row with a zombie file hanging around the cache dir.
 *
 * Both actions run on [Dispatchers.IO] — the DAO is already suspending,
 * but the filesystem call inside [delete] is blocking and we want them
 * on the same dispatcher for a single atomic-feeling unit of work.
 */
class ReportArchiveRepository(
    private val dao: ReportArchiveDao,
    private val pdfGenerator: HealthReportPdfGenerator,
) {

    fun observeAll(): Flow<List<ReportArchiveEntity>> = dao.observeAll()

    suspend fun findById(id: Long): ReportArchiveEntity? = withContext(Dispatchers.IO) {
        dao.findById(id)
    }

    /**
     * Register a freshly generated archive. Called by the view-model
     * immediately after a successful [HealthReportPdfGenerator.writeToCache]
     * with the stats taken from the returned [ReportArtifacts] (and the
     * totals from the [ReportSnapshot] used to render it).
     */
    suspend fun register(
        artifacts: ReportArtifacts,
        totalLogCount: Int,
        averageSeverity: Double?,
    ): Long = withContext(Dispatchers.IO) {
        dao.insert(
            ReportArchiveEntity(
                compressedFileName = artifacts.compressedFile.name,
                generatedAtEpochMillis = artifacts.generatedAtEpochMillis,
                uncompressedBytes = artifacts.uncompressedBytes,
                compressedBytes = artifacts.compressedBytes,
                totalLogCount = totalLogCount,
                averageSeverity = averageSeverity,
            ),
        )
    }

    /**
     * Two-step delete: file on disk first, then the Room row. If the
     * file delete fails we leave the row alone — the user sees the
     * entry stick around in the history list and can retry; the
     * alternative (row-first delete) would orphan the file and lie
     * about what's on disk.
     */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.findById(id) ?: return@withContext true
        val fileGone = pdfGenerator.deleteArchive(entity.compressedFileName)
        if (!fileGone) return@withContext false
        dao.delete(id)
        true
    }
}

package com.example.mob_dev_portfolio.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Photo attachment pipeline for symptom logs.
 *
 * Every path into storage goes through [addFromUri], which runs the
 * full sanitisation cascade:
 *
 *  1. Read the source stream (content-URI from the camera or gallery
 *     picker).
 *  2. Downscale to ≤ [MAX_LONGEST_EDGE] px on the longest edge (NFR-PA-01).
 *  3. Honour EXIF orientation so portraits don't save sideways, then
 *     re-encode as JPEG at `q=85`. Re-encoding from the decoded
 *     pixel buffer is what strips EXIF GPS and every other metadata
 *     block (NFR-PA-03) — no key-by-key blacklist needed.
 *  4. Encrypt with [PhotoEncryption] and write to
 *     `filesDir/symptom_photos/<uuid>.enc`.
 *  5. Insert the DAO row.
 *
 * Internal storage (not cache) because the OS can wipe `cacheDir`
 * under pressure, which would silently delete user-attached photos.
 * NFR-PA-02 calls for "app-scoped internal storage", which maps to
 * `Context.filesDir` — not visible to the system gallery, not
 * reachable via MediaStore, and included in per-user FBE.
 *
 * FR-PA-01 cap (≤ 3 per log) is enforced here rather than at the
 * DAO layer because 3 is a UX number that may move; the DB schema
 * is deliberately silent on it.
 */
open class SymptomPhotoRepository(
    private val context: Context,
    private val dao: SymptomPhotoDao,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Observe a log's photos as a reactive list. Maps each DAO row
     * to a domain [SymptomPhoto] carrying an absolute [File] so the
     * caller never has to care where the storage directory lives.
     */
    open fun observeForLog(logId: Long): Flow<List<SymptomPhoto>> =
        dao.observeForLog(logId).map { rows -> rows.map { it.toDomain() } }

    /**
     * One-shot list — used by the PDF generator, which pulls a
     * consistent snapshot rather than subscribing.
     */
    open suspend fun listForLog(logId: Long): List<SymptomPhoto> =
        dao.listForLog(logId).map { it.toDomain() }

    open suspend fun countForLog(logId: Long): Int = dao.countForLog(logId)

    /**
     * Ingests a single photo from a content-URI and returns the new
     * [SymptomPhoto], or `null` if the cap was already reached or
     * the source stream couldn't be decoded. Never throws on a
     * bad source — callers show the same "couldn't attach photo"
     * message either way, and throwing would kill the viewmodel
     * scope.
     */
    open suspend fun addFromUri(logId: Long, uri: Uri): SymptomPhoto? = withContext(Dispatchers.IO) {
        if (dao.countForLog(logId) >= MAX_PHOTOS_PER_LOG) return@withContext null
        val jpegBytes = runCatching { compressFromUri(uri) }.getOrNull() ?: return@withContext null
        val dir = photoDir().apply { if (!exists()) mkdirs() }
        val fileName = "${UUID.randomUUID()}.enc"
        val file = File(dir, fileName)
        runCatching {
            FileOutputStream(file).use { sink ->
                PhotoEncryption.encryptToStream(jpegBytes, sink)
            }
        }.getOrElse {
            file.delete()
            return@withContext null
        }
        val row = SymptomPhotoEntity(
            symptomLogId = logId,
            storageFileName = fileName,
            createdAtEpochMillis = nowProvider(),
        )
        val id = dao.insert(row)
        SymptomPhoto(
            id = id,
            symptomLogId = logId,
            file = file,
            createdAtEpochMillis = row.createdAtEpochMillis,
        )
    }

    /**
     * Delete a single photo — used by the "remove" button on the
     * editor thumbnail strip. File unlinked first (so a DB failure
     * leaves an orphan row, not an orphan file — orphan rows are
     * cheaper to clean up later).
     */
    open suspend fun delete(photoId: Long) = withContext(Dispatchers.IO) {
        val row = dao.getById(photoId) ?: return@withContext
        File(photoDir(), row.storageFileName).delete()
        dao.delete(photoId)
    }

    /**
     * Delete every photo (row + file) attached to a log. Called by
     * [com.example.mob_dev_portfolio.data.SymptomLogRepository.delete]
     * before the parent row is removed — at that point the files
     * are still discoverable via the DAO. The FK cascade on the
     * row side is a safety-net for callers that delete a log
     * directly in Room (e.g. a test).
     */
    open suspend fun deleteForLog(logId: Long) = withContext(Dispatchers.IO) {
        val rows = dao.listForLog(logId)
        val dir = photoDir()
        rows.forEach { File(dir, it.storageFileName).delete() }
        dao.deleteForLog(logId)
    }

    /**
     * Decode the encrypted file back to bytes. Used by the Compose
     * previewer's AsyncImage loader via an in-memory buffer — we
     * deliberately don't expose the plaintext file on disk, so
     * every display path goes through this decrypt-to-memory call.
     * Returns `null` if the file is missing or the GCM tag fails.
     */
    open suspend fun readBytes(photo: SymptomPhoto): ByteArray? = withContext(Dispatchers.IO) {
        if (!photo.file.exists()) return@withContext null
        runCatching { photo.file.inputStream().use { PhotoEncryption.decryptFromStream(it) } }.getOrNull()
    }

    /** See [readBytes]; this variant takes the DAO id directly. */
    open suspend fun readBytes(photoId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val row = dao.getById(photoId) ?: return@withContext null
        val file = File(photoDir(), row.storageFileName)
        if (!file.exists()) return@withContext null
        runCatching { file.inputStream().use { PhotoEncryption.decryptFromStream(it) } }.getOrNull()
    }

    private fun photoDir(): File = File(context.filesDir, PHOTOS_SUBDIR)

    private fun SymptomPhotoEntity.toDomain(): SymptomPhoto = SymptomPhoto(
        id = id,
        symptomLogId = symptomLogId,
        file = File(photoDir(), storageFileName),
        createdAtEpochMillis = createdAtEpochMillis,
    )

    /**
     * Decode → downscale → rotate → JPEG-encode. Two passes over
     * the stream: the first reads bounds only (`inJustDecodeBounds = true`)
     * so we can compute an `inSampleSize` that keeps the decode
     * buffer small; the second reads actual pixels. EXIF orientation
     * is read from a *third* pass on a ByteArray snapshot because
     * [ExifInterface] needs a seekable source and the incoming
     * content-URI stream is one-shot.
     */
    private fun compressFromUri(uri: Uri): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
            ?: error("Could not open source URI")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = calculateInSampleSize(longestEdge)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOptions)
            ?: error("Could not decode source bitmap")

        // Second pass: scale precisely to MAX_LONGEST_EDGE (inSampleSize
        // is only a power-of-two divisor so it'll typically leave the
        // longest edge somewhere between 1920 and 3840 px; a final
        // matrix scale brings it to exactly the target).
        val targetScale = if (maxOf(sampled.width, sampled.height) > MAX_LONGEST_EDGE) {
            MAX_LONGEST_EDGE.toFloat() / maxOf(sampled.width, sampled.height)
        } else {
            1f
        }

        val orientation = runCatching {
            ExifInterface(raw.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix().apply {
            if (targetScale != 1f) postScale(targetScale, targetScale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
        }

        val oriented = if (matrix.isIdentity) sampled else {
            val transformed = Bitmap.createBitmap(
                sampled,
                0,
                0,
                sampled.width,
                sampled.height,
                matrix,
                true,
            )
            if (transformed !== sampled) sampled.recycle()
            transformed
        }

        val out = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        oriented.recycle()
        return out.toByteArray()
    }

    private fun calculateInSampleSize(longestEdge: Int): Int {
        var sample = 1
        var edge = longestEdge
        while (edge / 2 >= MAX_LONGEST_EDGE) {
            edge /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        /**
         * FR-PA-01 cap. Enforced at the repository layer so tests can
         * drive it with a single `countForLog` call rather than
         * observing Flow state.
         */
        const val MAX_PHOTOS_PER_LOG: Int = 3

        /** NFR-PA-01 — longest edge in pixels. */
        const val MAX_LONGEST_EDGE: Int = 1920

        /** NFR-PA-01 — JPEG quality. */
        const val JPEG_QUALITY: Int = 85

        private const val PHOTOS_SUBDIR: String = "symptom_photos"
    }
}

/**
 * Domain model handed to the UI and PDF layers. Carries the resolved
 * [File] (pointing at the encrypted blob) so callers don't have to
 * know where photos live on disk — they call
 * [SymptomPhotoRepository.readBytes] to materialise plaintext.
 */
data class SymptomPhoto(
    val id: Long,
    val symptomLogId: Long,
    val file: File,
    val createdAtEpochMillis: Long,
)

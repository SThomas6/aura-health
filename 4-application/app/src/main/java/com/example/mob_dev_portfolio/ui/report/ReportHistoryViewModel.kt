package com.example.mob_dev_portfolio.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.report.HealthReportPdfGenerator
import com.example.mob_dev_portfolio.data.report.ReportArchiveEntity
import com.example.mob_dev_portfolio.data.report.ReportArchiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * View state for a single row in the report history list. Built from
 * [ReportArchiveEntity] but keeps the screen decoupled from Room — the
 * UI doesn't need to know about the entity's index column.
 */
data class ReportArchiveUiItem(
    val id: Long,
    val generatedAtEpochMillis: Long,
    val compressedFileName: String,
    val totalLogCount: Int,
    val averageSeverity: Double?,
    val compressedBytes: Long,
)

/**
 * One-shot event emitted from the view-model when the user taps Open
 * or Share, carrying the already-materialised uncompressed PDF file.
 * A [kotlin.Long] id makes it easy for the screen to consume-and-drop
 * each event without replaying stale ones across config changes.
 */
sealed interface ReportHistoryEvent {
    val id: Long
    data class Open(override val id: Long, val file: File) : ReportHistoryEvent
    data class Share(override val id: Long, val file: File) : ReportHistoryEvent
    data class DeleteFailed(override val id: Long, val message: String) : ReportHistoryEvent
    data class MaterialiseFailed(override val id: Long, val message: String) : ReportHistoryEvent
}

/**
 * Backs [ReportHistoryScreen]. The list comes straight from the
 * [ReportArchiveRepository] Flow, so a successful generate/delete from
 * anywhere in the app is reflected here without a manual refresh.
 *
 * Open and Share are async because they decompress the archive into a
 * transient preview file before handing a [File] back to the screen.
 * We route that through a single-element event channel rather than
 * stuffing the File into StateFlow, so re-collection after a config
 * change doesn't re-fire the intent.
 */
class ReportHistoryViewModel(
    private val archiveRepository: ReportArchiveRepository,
    private val pdfGenerator: HealthReportPdfGenerator,
) : ViewModel() {

    val items: StateFlow<List<ReportArchiveUiItem>> = archiveRepository.observeAll()
        .map { rows -> rows.map { it.toUi() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _events = MutableStateFlow<ReportHistoryEvent?>(null)
    val events: StateFlow<ReportHistoryEvent?> = _events.asStateFlow()

    /** Screen has handled an event — clear it so it doesn't replay. */
    fun consumeEvent() {
        _events.value = null
    }

    fun openReport(id: Long) = materialise(id, asShare = false)

    fun shareReport(id: Long) = materialise(id, asShare = true)

    private fun materialise(id: Long, asShare: Boolean) {
        viewModelScope.launch {
            val entity = archiveRepository.findById(id)
            if (entity == null) {
                _events.value = ReportHistoryEvent.MaterialiseFailed(
                    id = id,
                    message = "That report isn't in the archive anymore.",
                )
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                runCatching { pdfGenerator.materialisePreview(entity.compressedFileName) }
                    .getOrNull()
            }
            if (file == null) {
                _events.value = ReportHistoryEvent.MaterialiseFailed(
                    id = id,
                    message = "That report file is missing from storage.",
                )
                return@launch
            }
            _events.value = if (asShare) {
                ReportHistoryEvent.Share(id = id, file = file)
            } else {
                ReportHistoryEvent.Open(id = id, file = file)
            }
        }
    }

    /**
     * Delete is invoked from the confirmation dialog. The repository
     * deletes the file first, then the row — if the file delete fails
     * we surface a message and leave the list untouched.
     */
    fun deleteReport(id: Long) {
        viewModelScope.launch {
            val ok = runCatching { archiveRepository.delete(id) }.getOrDefault(false)
            if (!ok) {
                _events.value = ReportHistoryEvent.DeleteFailed(
                    id = id,
                    message = "Couldn't delete that report. Please try again.",
                )
            }
        }
    }

    private fun ReportArchiveEntity.toUi(): ReportArchiveUiItem = ReportArchiveUiItem(
        id = id,
        generatedAtEpochMillis = generatedAtEpochMillis,
        compressedFileName = compressedFileName,
        totalLogCount = totalLogCount,
        averageSeverity = averageSeverity,
        compressedBytes = compressedBytes,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return ReportHistoryViewModel(
                    archiveRepository = app.container.reportArchiveRepository,
                    pdfGenerator = app.container.healthReportPdfGenerator,
                ) as T
            }
        }
    }
}

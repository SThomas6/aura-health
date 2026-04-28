package com.example.mob_dev_portfolio.ui.report

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.report.HealthReportPdfGenerator
import com.example.mob_dev_portfolio.data.report.ReportArchiveRepository
import com.example.mob_dev_portfolio.data.report.ReportArtifacts
import com.example.mob_dev_portfolio.data.report.ReportRepository
import com.example.mob_dev_portfolio.data.report.ReportSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen state for [HealthReportScreen].
 *
 * The four cases map 1:1 to UI affordances:
 *  - [Idle]: show the "Generate report" CTA.
 *  - [Generating]: show a non-blocking progress indicator; suppress
 *    the CTA so the user can't double-fire the work.
 *  - [Ready]: show the preview + the export/share action, plus
 *    compression stats (uncompressed vs on-disk bytes) so the user
 *    can see the storage-optimization benefit.
 *  - [Error]: show an inline error card with a retry action.
 */
sealed interface HealthReportState {
    data object Idle : HealthReportState
    data object Generating : HealthReportState
    data class Ready(
        /**
         * The uncompressed preview PDF — consumed by [android.graphics.pdf.PdfRenderer]
         * and by the share intent. Materialised out of the persistent
         * compressed artifact by [HealthReportPdfGenerator.writeToCache].
         */
        val file: File,
        /** Persistent on-disk artifact (GZIP-compressed PDF). */
        val compressedFile: File,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
        val snapshot: ReportSnapshot,
    ) : HealthReportState
    data class Error(val message: String) : HealthReportState
}

/**
 * Orchestrates the "generate → preview → share" flow.
 *
 * The PDF write and GZIP compression both happen on [Dispatchers.IO] to
 * keep the UI thread free for recomposition — PdfDocument and
 * GZIPOutputStream are both plain blocking APIs. Everything else
 * (database snapshot, state transitions) is cheap enough to stay on
 * the main dispatcher.
 */
class HealthReportViewModel(
    private val repository: ReportRepository,
    private val pdfGenerator: HealthReportPdfGenerator,
    private val archiveRepository: ReportArchiveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<HealthReportState>(HealthReportState.Idle)
    val state: StateFlow<HealthReportState> = _state.asStateFlow()

    /**
     * User toggle for whether doctor-cleared symptom logs should be
     * included in the PDF. Default false — the assumption is that a
     * user sharing a PDF with a doctor doesn't want to flood it with
     * already-explained noise. Flipped via the "Show all symptoms" UI
     * affordance and re-read every time [generate] runs (so toggling
     * forces a regenerate).
     */
    private val _includeClearedLogs = MutableStateFlow(false)
    val includeClearedLogs: StateFlow<Boolean> = _includeClearedLogs.asStateFlow()

    fun setIncludeClearedLogs(include: Boolean) {
        _includeClearedLogs.value = include
    }

    fun generate() {
        // Guard against double-taps — the UI also hides the button
        // while we're busy, but a hardware-back + re-enter could
        // otherwise re-enter this path before the first one settles.
        if (_state.value is HealthReportState.Generating) return
        _state.value = HealthReportState.Generating
        viewModelScope.launch {
            runCatching {
                val snapshot = repository.loadReportSnapshot(
                    includeClearedLogs = _includeClearedLogs.value,
                )
                // Both the render (Canvas) and the compression
                // (GZIPOutputStream) are blocking, so the whole write is
                // off-main-thread.
                val artifacts: ReportArtifacts = withContext(Dispatchers.IO) {
                    pdfGenerator.writeToCache(snapshot)
                }
                // Persist a history row *after* the file lands. If the
                // insert throws (e.g. the unique index fires because of
                // a same-millisecond collision — practically impossible
                // but cheap to handle), we'd rather surface the error
                // than silently drop the row.
                archiveRepository.register(
                    artifacts = artifacts,
                    totalLogCount = snapshot.totalLogCount,
                    averageSeverity = snapshot.averageSeverity,
                )
                HealthReportState.Ready(
                    file = artifacts.previewFile,
                    compressedFile = artifacts.compressedFile,
                    uncompressedBytes = artifacts.uncompressedBytes,
                    compressedBytes = artifacts.compressedBytes,
                    snapshot = snapshot,
                )
            }.onSuccess { _state.value = it }
                .onFailure { error ->
                    // Log the full stack so a native crash or unexpected
                    // SQL failure is actually reachable from logcat — the
                    // error-surface UI only shows `message`, which for a
                    // RuntimeException wrapping a native crash tends to
                    // be generic ("Fatal signal 11…").
                    Log.e(TAG, "Report generation failed", error)
                    _state.value = HealthReportState.Error(
                        message = error.message
                            ?: "Something went wrong generating your report.",
                    )
                }
        }
    }

    /**
     * Drop the transient uncompressed preview file. Called from the
     * screen's onDispose so the on-disk footprint collapses back to
     * just the compressed artifact.
     */
    fun clearTransientArtifacts() {
        viewModelScope.launch(Dispatchers.IO) {
            pdfGenerator.clearTransientArtifacts()
        }
    }

    companion object {
        private const val TAG = "HealthReportVM"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return HealthReportViewModel(
                    repository = app.container.reportRepository,
                    pdfGenerator = app.container.healthReportPdfGenerator,
                    archiveRepository = app.container.reportArchiveRepository,
                ) as T
            }
        }
    }
}

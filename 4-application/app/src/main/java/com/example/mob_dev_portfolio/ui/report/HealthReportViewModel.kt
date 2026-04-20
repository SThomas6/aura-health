package com.example.mob_dev_portfolio.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.report.HealthReportPdfGenerator
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
 *  - [Ready]: show the preview + the export/share action.
 *  - [Error]: show an inline error card with a retry action; the
 *    snapshot may still be null here because we never reached the
 *    write stage.
 */
sealed interface HealthReportState {
    data object Idle : HealthReportState
    data object Generating : HealthReportState
    data class Ready(
        val file: File,
        val snapshot: ReportSnapshot,
    ) : HealthReportState
    data class Error(val message: String) : HealthReportState
}

/**
 * Orchestrates the "generate → preview → share" flow.
 *
 * The PDF write happens on [Dispatchers.IO] to keep the UI thread free
 * for recomposition — PdfDocument is a plain blocking API backed by
 * libpdfium underneath. Everything else is trivially cheap.
 *
 * Why we keep the snapshot alongside the File in [HealthReportState.Ready]:
 * the preview screen wants to surface the aggregate metrics (total
 * count, avg severity) in a live Compose card above the rendered PDF —
 * re-reading them from the PDF bytes would be absurd. The File is for
 * the PdfRenderer + share intent; the snapshot is for chrome.
 */
class HealthReportViewModel(
    private val repository: ReportRepository,
    private val pdfGenerator: HealthReportPdfGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow<HealthReportState>(HealthReportState.Idle)
    val state: StateFlow<HealthReportState> = _state.asStateFlow()

    fun generate() {
        // Guard against double-taps — the UI also hides the button
        // while we're busy, but a hardware-back + re-enter could
        // otherwise re-enter this path before the first one settles.
        if (_state.value is HealthReportState.Generating) return
        _state.value = HealthReportState.Generating
        viewModelScope.launch {
            runCatching {
                val snapshot = repository.loadReportSnapshot()
                val file = withContext(Dispatchers.IO) {
                    pdfGenerator.writeToCache(snapshot)
                }
                HealthReportState.Ready(file = file, snapshot = snapshot)
            }.onSuccess { _state.value = it }
                .onFailure {
                    _state.value = HealthReportState.Error(
                        message = it.message
                            ?: "Something went wrong generating your report.",
                    )
                }
        }
    }

    /** Reset back to idle — e.g. user dismissed the preview. */
    fun reset() {
        _state.value = HealthReportState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return HealthReportViewModel(
                    repository = app.container.reportRepository,
                    pdfGenerator = app.container.healthReportPdfGenerator,
                ) as T
            }
        }
    }
}

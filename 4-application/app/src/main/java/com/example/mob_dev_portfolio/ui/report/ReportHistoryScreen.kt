package com.example.mob_dev_portfolio.ui.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HistoryDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.getDefault())

/**
 * History of every generated PDF report, newest-first.
 *
 * The list is backed by a Room Flow via [ReportHistoryViewModel], so
 * new rows (from a fresh generate) and deletes show up automatically.
 * Each row offers three 48dp touch targets — Open, Share, Delete —
 * satisfying the accessibility requirement from the user story.
 *
 * Delete goes through an [AlertDialog] confirmation so a mistap can't
 * destroy a report. The deletion itself is two-step (file on disk
 * first, then Room row) inside the repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportHistoryScreen(
    onBack: () -> Unit,
    viewModel: ReportHistoryViewModel = viewModel(factory = ReportHistoryViewModel.Factory),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    // One-shot event pump. We consume events as soon as we see them so
    // a config-change recomposition doesn't re-fire the intent. Errors
    // go to a Toast because they're non-critical (the row is still
    // there, the user can retry) — an AlertDialog would be overkill.
    LaunchedEffect(event) {
        when (val e = event) {
            null -> Unit
            is ReportHistoryEvent.Open -> {
                openPdf(context, e.file)
                viewModel.consumeEvent()
            }
            is ReportHistoryEvent.Share -> {
                sharePdfFile(context, e.file)
                viewModel.consumeEvent()
            }
            is ReportHistoryEvent.DeleteFailed -> {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                viewModel.consumeEvent()
            }
            is ReportHistoryEvent.MaterialiseFailed -> {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                viewModel.consumeEvent()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report history") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("report_history_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyHistory(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("report_history_list"),
                contentPadding = PaddingValues(
                    horizontal = 20.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = items, key = { it.id }) { item ->
                    ReportHistoryRow(
                        item = item,
                        onOpen = { viewModel.openReport(item.id) },
                        onShare = { viewModel.shareReport(item.id) },
                        onDelete = { pendingDeleteId = item.id },
                    )
                }
            }
        }
    }

    val idToDelete = pendingDeleteId
    if (idToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this report?") },
            text = {
                Text(
                    "The compressed PDF will be removed from this device. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteReport(idToDelete)
                        pendingDeleteId = null
                    },
                    modifier = Modifier.testTag("report_history_confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteId = null },
                    modifier = Modifier.testTag("report_history_cancel_delete"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("report_history_delete_dialog"),
        )
    }
}

@Composable
private fun ReportHistoryRow(
    item: ReportArchiveUiItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("report_history_row_${item.id}"),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 16.dp,
                end = 8.dp,
                top = 12.dp,
                bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val generated = Instant.ofEpochMilli(item.generatedAtEpochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            Text(
                generated.format(HistoryDateFormat),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AuraMonoFamily,
            )
            val avg = item.averageSeverity
                ?.let { "avg ${"%.1f".format(it)}/10" }
                ?: "avg —"
            Text(
                "${item.totalLogCount} entries · $avg · ${formatBytes(item.compressedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 48dp IconButtons are the Material default; keeping them
                // explicit here so the acceptance criterion "≥ 48x48dp"
                // is visible at the call-site rather than implicit.
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("report_history_open_${item.id}"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open report",
                    )
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("report_history_share_${item.id}"),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share report",
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("report_history_delete_${item.id}"),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete report",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("report_history_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                "No reports yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Reports you generate appear here, newest first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${"%.1f".format(kb)} KB"
    val mb = kb / 1024.0
    return "${"%.2f".format(mb)} MB"
}

/**
 * Open the PDF in any installed viewer. `ACTION_VIEW` on a
 * `content://` URI (with temporary read permission) is the offline-
 * safe way to hand the file to an external app.
 */
private fun openPdf(context: Context, pdf: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdf,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Wrap in a chooser so we fail gracefully if the user has no PDF
    // viewer installed — the chooser shows "No apps can perform this
    // action" rather than crashing with ActivityNotFoundException.
    val chooser = Intent.createChooser(intent, "Open report with…").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            Toast.makeText(
                context,
                "No PDF viewer installed.",
                Toast.LENGTH_SHORT,
            ).show()
        }
}

private fun sharePdfFile(context: Context, pdf: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdf,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Aura Health Report")
        clipData = ClipData.newUri(context.contentResolver, "Aura Health Report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Share report via…").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

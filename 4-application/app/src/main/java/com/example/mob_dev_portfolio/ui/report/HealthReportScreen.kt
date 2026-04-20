package com.example.mob_dev_portfolio.ui.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.report.ReportSnapshot
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GeneratedAtFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.getDefault())

/**
 * Generate + preview a PDF health report.
 *
 * The screen flows through four states:
 *  1. **Idle** — a gradient hero with context + a "Generate report"
 *     CTA. Nothing exists on disk yet.
 *  2. **Generating** — a determinate loading card so the user knows
 *     the button registered; the CTA is hidden to prevent re-entry.
 *  3. **Ready** — aggregate-metric summary card, a compression stats
 *     chip (uncompressed vs on-disk bytes), then the live PDF preview,
 *     then Share/Regenerate actions at the bottom.
 *  4. **Error** — an inline card with a retry button; the report
 *     generation is offline-first so errors here are rare (empty
 *     database is a *valid* state, not an error).
 *
 * On dispose the transient uncompressed preview file is cleared so the
 * disk footprint collapses back to the compressed artifact alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthReportScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: HealthReportViewModel = viewModel(factory = HealthReportViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // When the screen goes away, wipe the transient preview PDF so that
    // — as required by the storage-optimization acceptance criterion —
    // the persistent disk footprint is just the compressed artifact.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearTransientArtifacts() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health report") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("report_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Single entry point into the report history — kept
                    // in the top bar so it's reachable from every state
                    // (Idle, Generating, Ready, Error). 48dp IconButton
                    // is the Material default and satisfies the "≥
                    // 48x48dp" acceptance criterion.
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("report_open_history"),
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Report history",
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            HealthReportState.Idle -> IdleContent(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onGenerate = viewModel::generate,
                onOpenHistory = onOpenHistory,
            )
            HealthReportState.Generating -> GeneratingContent(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            is HealthReportState.Ready -> ReadyContent(
                ready = current,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onShare = { sharePdf(context, current) },
                onRegenerate = viewModel::generate,
                onOpenHistory = onOpenHistory,
            )
            is HealthReportState.Error -> ErrorContent(
                message = current.message,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onRetry = viewModel::generate,
            )
        }
    }
}

@Composable
private fun IdleContent(
    modifier: Modifier = Modifier,
    onGenerate: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("report_idle"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard()
        InfoCard(
            icon = Icons.AutoMirrored.Filled.NoteAdd,
            title = "Chronological journal",
            body = "Your symptom logs + past AI insights, oldest to newest.",
        )
        InfoCard(
            icon = Icons.Filled.Description,
            title = "Aggregated summary",
            body = "Total entries and average severity computed straight from the database.",
        )
        InfoCard(
            icon = Icons.Filled.Share,
            title = "Ready for your doctor",
            body = "Preview inside Aura, then share the PDF from the action bar. Works completely offline.",
        )
        Spacer(Modifier.size(4.dp))
        Button(
            onClick = onGenerate,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("report_generate_cta"),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Generate report", fontWeight = FontWeight.SemiBold)
        }
        // Secondary affordance to the history list — sits right below
        // the primary CTA so it's impossible to miss. The top-bar icon
        // still works, but relying on that alone wasn't discoverable
        // enough for most users.
        OutlinedButton(
            onClick = onOpenHistory,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("report_idle_history_cta"),
        ) {
            Icon(Icons.Filled.History, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("View past reports")
        }
    }
}

@Composable
private fun HeroCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(auraHeroGradient())
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "OFFLINE · PDF",
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = AuraMonoFamily,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Your health, ready to share",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Generate a PDF summary of every symptom log and AI analysis you've recorded. Nothing leaves the device until you share it.",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GeneratingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("report_generating"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text(
                "Building your report…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    ready: HealthReportState.Ready,
    modifier: Modifier = Modifier,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = modifier.testTag("report_ready"),
    ) {
        // Top chrome — summary card matches the one rendered at the
        // bottom of the PDF so the user can confirm the numbers before
        // they share anything, then a compression chip surfacing the
        // storage optimization.
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCard(snapshot = ready.snapshot)
            // Quick jump to the history of previous reports. Keeping it
            // as an OutlinedButton here (rather than only in the top-bar
            // icon) so users who haven't noticed the header affordance
            // still land on it on the way to the share/regenerate row.
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("report_ready_history_cta"),
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("View past reports")
            }
        }
        PdfPreview(
            file = ready.file,
            modifier = Modifier.weight(1f),
            horizontalPadding = 20.dp,
        )
        // Bottom action bar — share is primary, regenerate is secondary
        // (you might want fresh numbers after logging another symptom).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRegenerate,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("report_regenerate"),
            ) {
                Text("Regenerate")
            }
            Button(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("report_share"),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Share PDF", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SummaryCard(snapshot: ReportSnapshot) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("report_summary_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Report summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(
                    label = "Total entries",
                    value = snapshot.totalLogCount.toString(),
                    testTag = "report_metric_total",
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "Avg severity",
                    value = snapshot.averageSeverity
                        ?.let { "${"%.2f".format(it)}/10" }
                        ?: "—",
                    testTag = "report_metric_avg",
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = "AI runs",
                    value = snapshot.analyses.size.toString(),
                    testTag = "report_metric_ai",
                    modifier = Modifier.weight(1f),
                )
            }
            val generated = Instant.ofEpochMilli(snapshot.generatedAtEpochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            Text(
                "Generated ${generated.format(GeneratedAtFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = AuraMonoFamily,
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
            .testTag(testTag),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AuraMonoFamily,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("report_error"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Report failed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("report_retry"),
        ) {
            Text("Try again")
        }
    }
}

/**
 * Share the generated PDF via the standard Android share-sheet.
 *
 * FileProvider wraps the app-private file into a `content://` URI with
 * temporary read permission so the receiving app (Gmail, Drive, etc.)
 * can pull the bytes without needing access to the rest of our cache.
 *
 * If the transient preview has been evicted (app was backgrounded for
 * long enough that cache was cleaned), we re-materialise it from the
 * compressed persistent artifact before firing the intent — so the
 * share action is robust across cache pressure.
 *
 * `ClipData.newUri` is set on the intent in addition to the stream
 * extra; some share targets (Gmail in particular) read attachments
 * from `ClipData` rather than `EXTRA_STREAM` when both exist.
 */
private fun sharePdf(context: Context, ready: HealthReportState.Ready) {
    val pdf: File = if (ready.file.exists()) {
        ready.file
    } else {
        // Preview was evicted; rebuild from the compressed persistent
        // copy. The generator is available through the application
        // container — this is a one-off, synchronous decompress of
        // a ~100 KB file so blocking here is acceptable.
        val app = context.applicationContext as com.example.mob_dev_portfolio.AuraApplication
        app.container.healthReportPdfGenerator.materialisePreview(ready.compressedFile.name)
            ?: ready.file // last-resort; FileProvider will fail cleanly
    }
    val authority = "${context.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, pdf)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Aura Health Report")
        // Some email clients prefer ClipData for attachments.
        clipData = ClipData.newUri(context.contentResolver, "Aura Health Report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Share report via…").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}



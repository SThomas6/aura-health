package com.example.mob_dev_portfolio.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.ai.AnalysisRun
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DetailDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

/**
 * Detail view for a single persisted analysis run.
 *
 * This is the landing screen for the notification deep-link (via
 * [com.example.mob_dev_portfolio.ui.DeepLinkTarget.AnalysisRun]) and for
 * taps on a history row. Everything is read back from Room through the
 * [AnalysisDetailViewModel], so the view works offline — no network
 * call is made here; the Gemini response that produced this run is
 * already cached in the database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisDetailScreen(
    runId: Long,
    onBack: () -> Unit,
    viewModel: AnalysisDetailViewModel = viewModel(
        factory = AnalysisDetailViewModel.factory(runId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    // Only offer the delete action when we're actually looking at a
    // row — no point in the Loading placeholder or a NotFound state.
    val canDelete = state is AnalysisDetailState.Loaded

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis detail") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("analysis_detail_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (canDelete) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.testTag("analysis_detail_delete"),
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Delete analysis",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            AnalysisDetailState.Loading -> LoadingState(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            AnalysisDetailState.NotFound -> NotFoundState(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            is AnalysisDetailState.Loaded -> LoadedContent(
                run = current.run,
                linkedLogCount = current.linkedLogIds.size,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this analysis?") },
            text = {
                Text(
                    "This will permanently remove the run from your history. The symptom logs it was based on will not be affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Dismiss the dialog first so the user sees the
                        // back-nav land cleanly; the VM then deletes and
                        // pops.
                        showDeleteDialog = false
                        viewModel.delete(onDeleted = onBack)
                    },
                    modifier = Modifier.testTag("analysis_detail_delete_confirm"),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("analysis_detail_delete_dialog"),
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("analysis_detail_loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

@Composable
private fun NotFoundState(modifier: Modifier = Modifier) {
    // Defensive: the user followed a stale deep-link (the row was
    // deleted after the notification was posted but before they tapped
    // it). Today there's no delete action, but rebooting can replay old
    // notifications, so it's a real edge case.
    Box(
        modifier = modifier
            .padding(24.dp)
            .testTag("analysis_detail_not_found"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This analysis is no longer available.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "It may have been removed. Head back to the history list to see your recent runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadedContent(
    run: AnalysisRun,
    linkedLogCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("analysis_detail_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GuidanceHero(run = run)
        SummaryCard(run = run)
        LinkedLogsFooter(count = linkedLogCount)
    }
}

/**
 * Gradient hero restating the bucket + headline. The pill echoes the
 * history list so the two surfaces feel like one object opening up,
 * and the timestamp uses the mono family reserved for quantitative
 * readouts (matches the row on the history screen).
 */
@Composable
private fun GuidanceHero(run: AnalysisRun) {
    val completed = Instant.ofEpochMilli(run.completedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(auraHeroGradient())
            .padding(20.dp)
            .testTag("analysis_detail_hero"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "GEMINI · PRIVATE",
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = AuraMonoFamily,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                run.headline,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("analysis_detail_headline"),
            )
            Text(
                completed.format(DetailDateFormat),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = AuraMonoFamily,
                modifier = Modifier.testTag("analysis_detail_timestamp"),
            )
            // Pill mirrors the one on the list row so the two surfaces
            // feel related. Rendered on top of the gradient the pill
            // itself uses the theme containers so it still reads at a
            // glance.
            GuidancePill(guidance = run.guidance)
        }
    }
}

/**
 * The full markdown summary from Gemini. We route it through the same
 * [MarkdownContent] renderer the live Analysis screen uses so bullets,
 * bold and headings render consistently — the user should see the
 * *same* rendered output they saw immediately after the run completed.
 */
@Composable
private fun SummaryCard(run: AnalysisRun) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_detail_summary_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Correlations & contributing factors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MarkdownContent(
                text = run.summaryText,
                modifier = Modifier.testTag("analysis_detail_summary"),
            )
        }
    }
}

/**
 * Small footer telling the user how many symptom logs fed this run —
 * the DB stores this via the `analysis_run_logs` cross-ref so the count
 * is authoritative, not a re-read of "recent logs". Zero is possible in
 * pathological cases (log deleted afterwards, etc.), so we special-case
 * the copy rather than showing "0 symptom logs analysed".
 */
@Composable
private fun LinkedLogsFooter(count: Int) {
    val label = when (count) {
        0 -> "Generated from your logs at the time of the analysis."
        1 -> "1 symptom log analysed."
        else -> "$count symptom logs analysed."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("analysis_detail_linked_logs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = AuraMonoFamily,
        )
    }
    Spacer(Modifier.height(4.dp))
}

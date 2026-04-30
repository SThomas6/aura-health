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
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.mob_dev_portfolio.data.ai.AnalysisSummaryFormatter
import com.example.mob_dev_portfolio.data.ai.AnalysisSummaryFormatter.SeverityTier
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
    onGenerateReport: () -> Unit = {},
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
                onGenerateReport = onGenerateReport,
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
    onGenerateReport: () -> Unit,
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
        // Always render the NHS disclaimer — the model is prompted to
        // emit an `NHS_REFERENCE:` marker but we can't rely on it being
        // present (older runs predate the instruction, and the model
        // occasionally drops it). Showing our own copy guarantees the
        // "check NHS for full symptoms" guidance is surfaced every time
        // the user reads an analysis, regardless of what Gemini emits.
        NhsReferenceCard()
        // Surface which Health Connect metrics were actually
        // incorporated into this run so the user can see at a glance
        // whether the AI took their activity/sleep/vitals into account.
        HealthDataConsideredCard(metrics = run.healthMetricsShortLabels)
        // Quick jump to the PDF report — the analysis detail is a
        // natural "share with my doctor" moment, so surfacing the
        // report entry point here saves a trip back to Home.
        Button(
            onClick = onGenerateReport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("analysis_detail_generate_report"),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Generate health report", fontWeight = FontWeight.SemiBold)
        }
        LinkedLogsFooter(count = linkedLogCount)
    }
}

/**
 * Gradient hero restating the bucket + headline. The pill echoes the
 * history list so the two surfaces feel like one object opening up,
 * and the timestamp uses the mono family reserved for quantitative
 * readouts (matches the row on the history screen).
 *
 * The background colour reflects the severity tier of the result:
 *   - AllClear (green-leaning) → the usual mint hero gradient
 *   - Watch    (amber) → an amber wash telling the user there's
 *     something worth raising with a clinician
 *   - Urgent   (red) → a red wash when the summary contains 999 /
 *     A&E / "life-threatening" style language
 *
 * The hero was previously a fixed gradient regardless of outcome; the
 * user asked for a stronger at-a-glance cue here because the headline
 * alone was easy to gloss over.
 */
@Composable
private fun GuidanceHero(run: AnalysisRun) {
    val completed = Instant.ofEpochMilli(run.completedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    val tier = AnalysisSummaryFormatter.severityTier(run.guidance, run.summaryText)
    // Pick a solid semantic colour for Watch/Urgent; keep the gradient
    // for AllClear because green is already its visual language.
    // Chosen to read well over the white foreground content already
    // in the hero — changing text colours would ripple into pill styling
    // and testTag-based assertions, which isn't worth the blast radius.
    val heroModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(28.dp))
        .let { base ->
            when (tier) {
                SeverityTier.AllClear -> base.background(auraHeroGradient())
                // Warm amber — reads clearly as "worth a look" on both
                // light and dark themes without being alarming.
                SeverityTier.Watch -> base.background(Color(0xFFB5651D))
                // Material error-red: unambiguous stop-signal for runs
                // where Gemini flagged potentially urgent patterns.
                SeverityTier.Urgent -> base.background(Color(0xFFB00020))
            }
        }
        .padding(20.dp)
        .testTag("analysis_detail_hero")
    Box(
        modifier = heroModifier,
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
            // Strip the internal `GUIDANCE:` / `NHS_REFERENCE:` markers
            // before rendering — those are classifier hints for our own
            // pipeline, not content the user should see inline. The pill
            // and the dedicated NHS card below replay them in a tidier
            // form.
            MarkdownContent(
                text = AnalysisSummaryFormatter.stripInternalMarkers(run.summaryText),
                modifier = Modifier.testTag("analysis_detail_summary"),
            )
        }
    }
}

/**
 * Persistent disclaimer pointing the user at the NHS symptom checker.
 *
 * The Gemini prompt now instructs the model to name *possible*
 * life-threatening conditions (cancers, cardiac events, sepsis, etc.)
 * where the pattern fits — that is clinically useful but can also
 * alarm someone reading their own logs without context. Tethering
 * every analysis to the NHS "check full symptoms" entry point gives
 * the user an authoritative next step and nudges them away from
 * treating the AI output as a standalone diagnosis.
 *
 * Rendered as a distinct card (rather than inline in the summary) so
 * it is impossible to miss and identical across every run.
 */
@Composable
private fun NhsReferenceCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_detail_nhs_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Not a diagnosis — check the NHS for full symptom information",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "This analysis is not a diagnosis. For any condition named above, or if anything you're feeling worries you, look up the full symptom list at www.nhs.uk. Call 111 for urgent-but-not-emergency advice, or 999 if symptoms are severe or life-threatening.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Card listing the Health Connect metrics that contributed data to this
 * run. The list is persisted at analysis time (see
 * [com.example.mob_dev_portfolio.data.ai.AnalysisRunEntity.healthMetricsCsv])
 * so re-opening an older run still shows what was considered, without
 * re-querying Health Connect (grants may have changed since).
 *
 * Three visual states:
 *
 *   - `null` metrics — pre-Health-Connect-integration run. We render a
 *     neutral "no health data" note rather than hide the card, so the
 *     user can see what *wasn't* considered without confusion.
 *   - Empty list — integration active but nothing readable (toggles all
 *     off, or no records in the window).
 *   - Non-empty list — chip row with the short labels.
 */
@Composable
private fun HealthDataConsideredCard(metrics: List<String>?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analysis_detail_health_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Health data considered",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                metrics == null -> Text(
                    "No Health Connect data was considered for this run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                metrics.isEmpty() -> Text(
                    "Health Connect was enabled, but no metrics had readable data in the relevant time windows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = metrics.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = AuraMonoFamily,
                    modifier = Modifier.testTag("analysis_detail_health_chips"),
                )
            }
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

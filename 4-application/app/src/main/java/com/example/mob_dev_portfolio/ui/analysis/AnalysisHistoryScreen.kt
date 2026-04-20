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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisRun
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RowDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

/**
 * History list for persisted AI analysis runs.
 *
 * The Analysis tab lands here by default — acceptance criteria expect the
 * user to "view their past AI analysis runs" first, with the option to
 * trigger a new one via the extended FAB. Tapping a row deep-links to
 * [AnalysisDetailScreen] with the Room rowId embedded in the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisHistoryScreen(
    onOpenRun: (Long) -> Unit,
    onRunNewAnalysis: () -> Unit,
    viewModel: AnalysisHistoryViewModel = viewModel(factory = AnalysisHistoryViewModel.Factory),
) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AI analysis") })
        },
        // FAB only appears when there's at least one past run. When the
        // list is empty we hand the CTA over to [EmptyHistoryState],
        // which renders a single centred "Run AI analysis" button — so
        // the user isn't looking at two competing entry points on an
        // otherwise blank screen.
        floatingActionButton = {
            if (runs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onRunNewAnalysis,
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                    text = { Text("Run analysis") },
                    modifier = Modifier.testTag("analysis_run_fab"),
                )
            }
        },
    ) { padding ->
        if (runs.isEmpty()) {
            EmptyHistoryState(
                onRunNewAnalysis = onRunNewAnalysis,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("analysis_history_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    bottom = 96.dp, // leave room under the FAB
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = runs, key = { it.id }) { run ->
                    AnalysisHistoryRow(
                        run = run,
                        onClick = { onOpenRun(run.id) },
                    )
                }
            }
        }
    }
}

/**
 * One row on the history list.
 *
 * The row summarises a run at a glance: the guidance headline (bold), the
 * bucket as a pill on the right, and the completion timestamp in the
 * monospaced family the rest of the app uses for quantitative readouts.
 */
@Composable
private fun AnalysisHistoryRow(run: AnalysisRun, onClick: () -> Unit) {
    val completed = Instant.ofEpochMilli(run.completedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("analysis_history_row_${run.id}"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    run.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    completed.format(RowDateFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = AuraMonoFamily,
                )
            }
            GuidancePill(guidance = run.guidance)
            Spacer(Modifier.size(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Compact bucket pill. `Clear` tints mint-ish to echo the positive
 * hero gradient; `SeekAdvice` tints error-ish so the list skim is
 * immediately reader-friendly — the user doesn't have to read the
 * headline text to know which rows matter.
 */
@Composable
internal fun GuidancePill(guidance: AnalysisGuidance) {
    val (bg, fg, label) = when (guidance) {
        AnalysisGuidance.Clear -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "CLEAR",
        )
        AnalysisGuidance.SeekAdvice -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "SEEK ADVICE",
        )
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontFamily = AuraMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Empty-state shown until the user runs their first analysis. We keep
 * the CTA prominent here so "I opened the tab, now what?" is a one-tap
 * question.
 */
@Composable
private fun EmptyHistoryState(
    onRunNewAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(24.dp)
            .testTag("analysis_history_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(auraHeroGradient()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                "No analyses yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Run your first AI correlation check to see guidance and a full summary here. Past runs stay available offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ExtendedFloatingActionButton(
                onClick = onRunNewAnalysis,
                icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                text = { Text("Run AI analysis") },
                modifier = Modifier
                    .sizeIn(minHeight = 56.dp)
                    .testTag("analysis_empty_cta"),
            )
        }
    }
}

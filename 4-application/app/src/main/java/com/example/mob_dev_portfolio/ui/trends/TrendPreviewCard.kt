package com.example.mob_dev_portfolio.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Home-card preview of the Trends dashboard.
 *
 * Intentionally chromeless: no range picker, no overlay chips, no
 * symptom dropdown — Material's density guidance says "don't replicate
 * the destination's controls in its entry card". We pick sensible
 * defaults (most-frequent symptom, 1 month, no overlays) so the card
 * works the moment one log exists; the user drills in for anything
 * richer.
 *
 * The whole card is a single tap target that routes to
 * [com.example.mob_dev_portfolio.ui.trends.TrendVisualisationScreen]
 * — tapping the chart itself satisfies the requirement that "when you
 * press on the graph you should be able to go onto its own trend
 * page".
 */
@Composable
fun TrendPreviewCard(
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrendVisualisationViewModel = viewModel(factory = TrendVisualisationViewModel.Factory),
) {
    // Home uses a distinct state flow that ignores the in-memory
    // multi-select and instead renders whichever single overlay the
    // user most recently toggled on inside the fullscreen dashboard
    // (falling back to Sleep). Keeps the preview card in step with the
    // user's current focus without replicating the full controls here.
    val state by viewModel.homePreviewState.collectAsStateWithLifecycle()

    Card(
        onClick = onOpenFull,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_trends_preview"),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Trends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.selectedSymptom?.let { "$it · last 7 days" }
                            ?: "Chart severity over time",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The chart itself. Compact mode drops gridlines and sample
            // dots so the Home card stays visually calm — the fullscreen
            // page owns the dense reading experience.
            val palette = state.series.indices.map { chartColor(it) }
            TrendLineChart(
                series = state.series,
                colors = palette,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .testTag("home_trends_preview_chart"),
            )

            // Legend strip — single row, swatch + label. Skipped when
            // nothing is charted so the empty-state text inside the
            // chart itself isn't upstaged.
            if (state.series.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.series.take(3).forEachIndexed { i, s ->
                        val swatch = chartColor(i)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(swatch),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                s.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.series.size > 3) {
                        Text(
                            "+${state.series.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

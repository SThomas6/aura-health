package com.example.mob_dev_portfolio.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily

/**
 * Fullscreen detail view for a single Health Connect metric. The user
 * opens this by tapping a card in [HealthDashboardSection]. Offers a
 * Day/Week/Month/Year segmented toggle (defaulting to Week) that reloads
 * the series on change.
 *
 * Below the chart, a small summary panel surfaces total / average /
 * min / max / latest — scoped to the currently-selected range so the
 * numbers line up with the chart rather than always being 7-day values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthMetricDetailScreen(
    metricStorageKey: String,
    onBack: () -> Unit,
    viewModel: HealthMetricDetailViewModel = viewModel(factory = HealthMetricDetailViewModel.factory(metricStorageKey)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metric = state.metric
    val accent = metric.accentColor()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metric.displayLabel) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("metric_detail_back"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RangeToggle(
                selected = state.range,
                onSelect = viewModel::setRange,
            )
            ChartCard(state = state, accent = accent)
            SummaryCard(metric = metric, series = state.series)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeToggle(
    selected: HealthHistoryRepository.Range,
    onSelect: (HealthHistoryRepository.Range) -> Unit,
) {
    // Order matters — rendered left-to-right from narrowest to widest.
    val options = listOf(
        HealthHistoryRepository.Range.Day to "Day",
        HealthHistoryRepository.Range.Week to "Week",
        HealthHistoryRepository.Range.Month to "Month",
        HealthHistoryRepository.Range.Year to "Year",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (range, label) ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag("range_${range.name.lowercase()}"),
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ChartCard(
    state: HealthMetricDetailState,
    accent: androidx.compose.ui.graphics.Color,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val metric = state.metric
            val headline = state.series?.summary?.let { HealthMetricFormat.headline(metric, it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        HealthMetricFormat.headlineCaption(metric),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            HealthMetricFormat.formatValue(metric, headline),
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = AuraMonoFamily,
                            color = accent,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            HealthMetricFormat.unitLabel(metric),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
            // Height is a static value for adults — a chart across time
            // adds no information, so we show an explanatory note instead
            // of a flat line. Every other metric falls through to the
            // normal chart rendering below.
            val isStaticHeight = metric == com.example.mob_dev_portfolio.data.health.HealthConnectMetric.Height
            if (isStaticHeight) {
                Text(
                    "Height is treated as a constant reading — tracking it over time isn't useful once you're an adult. Update the value in Health Connect if it ever changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (state.loading || state.series == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        MetricChart(
                            points = state.series.points,
                            accent = accent,
                            style = metric.defaultChartStyle(),
                            metric = metric,
                            range = state.range,
                            height = 240.dp,
                            showAxis = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Axis legend + affordance hint. Kept in one line so it
                // doesn't clutter the card — the axis labels on the chart
                // itself are self-documenting for power users, this spells
                // it out for first-time viewers.
                Text(
                    text = buildString {
                        append("X-axis: ")
                        append(xAxisLabel(state.range))
                        append("   ·   Y-axis: ")
                        append(HealthMetricFormat.unitLabel(metric))
                        append("   ·   Press & hold to inspect a value")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Human-readable description of what the X-axis represents at the
 * given range. Used in the axis legend strip under the chart so the
 * user doesn't have to squint at the tick labels to guess the unit.
 */
private fun xAxisLabel(range: HealthHistoryRepository.Range): String = when (range) {
    HealthHistoryRepository.Range.Day -> "Hour of day"
    HealthHistoryRepository.Range.Week -> "Day (last 7 days)"
    HealthHistoryRepository.Range.Month -> "Date (last 30 days)"
    // HalfYear isn't currently exposed from this screen's range picker,
    // but the enum is shared so we label it anyway for when it is.
    HealthHistoryRepository.Range.HalfYear -> "Week (last 6 months)"
    HealthHistoryRepository.Range.Year -> "Month (last 12 months)"
}

@Composable
private fun SummaryCard(
    metric: HealthConnectMetric,
    series: HealthHistoryRepository.Series?,
) {
    val summary = series?.summary ?: HealthHistoryRepository.Summary.Empty
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Range statistics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Different metrics care about different aggregates. Steps
            // have a meaningful total; weight doesn't. Only emit rows
            // that are applicable to this metric.
            summary.total?.let { StatRow("Total", HealthMetricFormat.formatValue(metric, it), HealthMetricFormat.unitLabel(metric)) }
            summary.average?.let { StatRow("Average", HealthMetricFormat.formatValue(metric, it), HealthMetricFormat.unitLabel(metric)) }
            summary.maximum?.let { StatRow("Maximum", HealthMetricFormat.formatValue(metric, it), HealthMetricFormat.unitLabel(metric)) }
            summary.minimum?.let { StatRow("Minimum", HealthMetricFormat.formatValue(metric, it), HealthMetricFormat.unitLabel(metric)) }
            summary.latest?.let { StatRow("Latest", HealthMetricFormat.formatValue(metric, it), HealthMetricFormat.unitLabel(metric)) }
            if (
                summary.total == null && summary.average == null &&
                summary.maximum == null && summary.minimum == null &&
                summary.latest == null
            ) {
                Text(
                    "No readings in this range yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontFamily = AuraMonoFamily)
        Spacer(Modifier.size(4.dp))
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

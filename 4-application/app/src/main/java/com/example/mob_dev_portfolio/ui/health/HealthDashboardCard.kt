package com.example.mob_dev_portfolio.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily

/**
 * Home-screen "Health data" section. Renders a small card per enabled
 * metric with a 7-day mini chart, the metric's headline number + unit,
 * and a chevron that deep-links into the fullscreen detail.
 *
 * Hidden entirely when Health Connect is unavailable (the settings card
 * on Home still exposes the install path) or the user has disconnected —
 * we don't want to pad Home with an empty "Health data" header when it
 * would have nothing to show.
 */
@Composable
fun HealthDashboardSection(
    onOpenMetric: (HealthConnectMetric) -> Unit,
    viewModel: HealthDashboardViewModel = viewModel(factory = HealthDashboardViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    if (!state.sdkAvailable) return
    if (!state.connectionActive) return
    if (state.loading && state.cards.isEmpty()) {
        LoadingPlaceholder()
        return
    }
    if (state.cards.isEmpty()) return

    // Persist the expanded/collapsed choice across process death via
    // rememberSaveable — tapping Home mid-flow shouldn't reset the user's
    // preference about whether the dashboard stays out of the way.
    //
    // Default = true. The user wants the health data visible on arrival
    // because it's a primary surface for them; they can still collapse
    // the section and that collapse sticks for future visits.
    var expanded by rememberSaveable { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "health_section_chevron",
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("health_section_header")
                .padding(vertical = 4.dp),
        ) {
            Text(
                "Health data",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "7 DAYS" else "${state.cards.size} METRICS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse health data" else "Expand health data",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.cards.forEach { card ->
                    MetricMiniCard(card = card, onClick = { onOpenMetric(card.metric) })
                }
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MetricMiniCard(
    card: MetricCardState,
    onClick: () -> Unit,
) {
    val accent = card.metric.accentColor()
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("health_metric_card_${card.metric.storageKey}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent),
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        card.metric.displayLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        HealthMetricFormat.headlineCaption(card.metric),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            HealthMetricFormat.formatValue(card.metric, card.headlineValue),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = AuraMonoFamily,
                            color = accent,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            HealthMetricFormat.unitLabel(card.metric),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Height is effectively static for adults — we skip the chart
            // because a flat line reading "same value seven days running"
            // adds nothing and just consumes vertical space. The headline
            // value above is the entire signal for this metric.
            if (card.metric != HealthConnectMetric.Height) {
                MetricChart(
                    points = card.series.points,
                    accent = accent,
                    style = card.metric.defaultChartStyle(),
                    metric = card.metric,
                    range = card.series.range,
                    height = 72.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

package com.example.mob_dev_portfolio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogSymptomClick: () -> Unit,
    onViewHistoryClick: () -> Unit,
    onOpenLog: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    themeViewModel: ThemePrefsViewModel = viewModel(factory = ThemePrefsViewModel.Factory),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val themeError by themeViewModel.error.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(themeError) {
        themeError?.let { error ->
            val result = snackbarHost.showSnackbar(
                message = error.message,
                actionLabel = "Retry",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                themeViewModel.retryLastError()
            } else {
                themeViewModel.dismissError()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Aura Health") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroCard(logCount = logs.size, onLogSymptomClick = onLogSymptomClick)
            InsightsDashboard(insights = insights)
            QuickActionCard(
                title = "View history",
                subtitle = "Review and search your past logs",
                onClick = onViewHistoryClick,
            )
            ThemePickerCard(
                current = themeMode,
                onSelect = themeViewModel::setThemeMode,
            )
            if (logs.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                logs.take(3).forEach { log ->
                    Card(
                        onClick = { onOpenLog(log.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .testTag("home_recent_${log.id}"),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(log.symptomName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                log.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(logCount: Int, onLogSymptomClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FavoriteBorder, contentDescription = null)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "  Welcome back",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (logCount == 0) "Start tracking your health." else "You've tracked $logCount ${if (logCount == 1) "entry" else "entries"}.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Log symptoms as they happen so patterns become easier to spot.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onLogSymptomClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("btn_home_log"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.height(4.dp))
                Text("  Log a symptom")
            }
        }
    }
}

@Composable
private fun QuickActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePickerCard(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeModeOption(ThemeMode.System, "System", Icons.Filled.SettingsBrightness),
        ThemeModeOption(ThemeMode.Light, "Light", Icons.Filled.LightMode),
        ThemeModeOption(ThemeMode.Dark, "Dark", Icons.Filled.DarkMode),
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val selected = option.mode == current
                    SegmentedButton(
                        selected = selected,
                        onClick = { onSelect(option.mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(
                                active = selected,
                                activeContent = {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                    )
                                },
                                inactiveContent = {
                                    Icon(
                                        option.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("theme_${option.mode.name.lowercase()}"),
                        label = {
                            Text(
                                option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }
    }
}

private data class ThemeModeOption(
    val mode: ThemeMode,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun InsightsDashboard(insights: HomeInsights) {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("insights_dashboard"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Insights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            StatsRow(insights = insights)
            TrendSection(insights = insights)
        }
    }
}

@Composable
private fun StatsRow(insights: HomeInsights) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth > 480.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell(
                    label = "Total",
                    value = insights.totalLogs.toString(),
                    tag = "stat_total",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Avg severity",
                    value = insights.averageSeverity?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    tag = "stat_avg_severity",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Top symptom",
                    value = insights.mostCommonSymptom ?: "—",
                    helper = insights.mostCommonSymptom?.let { "${insights.mostCommonSymptomCount}×" },
                    tag = "stat_top_symptom",
                    modifier = Modifier.weight(1.4f),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCell(
                        label = "Total",
                        value = insights.totalLogs.toString(),
                        tag = "stat_total",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Avg severity",
                        value = insights.averageSeverity?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                        tag = "stat_avg_severity",
                        modifier = Modifier.weight(1f),
                    )
                }
                StatCell(
                    label = "Top symptom",
                    value = insights.mostCommonSymptom ?: "—",
                    helper = insights.mostCommonSymptom?.let { "${insights.mostCommonSymptomCount}× logged" },
                    tag = "stat_top_symptom",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    tag: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .heightIn(min = 72.dp)
            .testTag(tag),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (helper != null) {
                Text(
                    helper,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TrendSection(insights: HomeInsights) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Last 7 days",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val total = insights.trend.sumOf { it.count }
            Text(
                "$total ${if (total == 1) "log" else "logs"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TrendChart(
            trend = insights.trend,
            peak = insights.trendPeak,
            barColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .testTag("trend_chart"),
        )
        TrendLabels(insights.trend)
    }
}

@Composable
private fun TrendChart(
    trend: List<DailyCount>,
    peak: Int,
    barColor: androidx.compose.ui.graphics.Color,
    trackColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { 6.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val effectivePeak = if (peak > 0) peak else 1

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val count = trend.size.coerceAtLeast(1)
        val totalGap = gapPx * (count - 1)
        val barWidth = ((canvasWidth - totalGap) / count).coerceAtLeast(2f)
        val topInset = strokePx
        val plotHeight = canvasHeight - topInset

        trend.forEachIndexed { index, entry ->
            val x = index * (barWidth + gapPx)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, topInset),
                size = Size(barWidth, plotHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = strokePx),
            )
            val heightFraction = entry.count.toFloat() / effectivePeak.toFloat()
            val barHeight = (plotHeight * heightFraction).coerceAtLeast(if (entry.count == 0) 0f else strokePx * 2)
            if (barHeight > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, topInset + plotHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                )
            }
        }
    }
}

@Composable
private fun TrendLabels(trend: List<DailyCount>) {
    val formatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        trend.forEach { entry ->
            Text(
                entry.date.format(formatter).take(3),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("trend_label_${entry.date}"),
            )
        }
    }
}

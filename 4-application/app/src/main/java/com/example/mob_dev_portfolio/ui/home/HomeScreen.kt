package com.example.mob_dev_portfolio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import com.example.mob_dev_portfolio.ui.components.SeverityEdge
import com.example.mob_dev_portfolio.ui.components.SeverityPill
import com.example.mob_dev_portfolio.ui.components.SeverityRing
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.theme.AuraInk
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import com.example.mob_dev_portfolio.ui.theme.severityColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────
// Home — top-level scaffold.
//
// The redesign replaces the Material 3 TopAppBar with a custom greeting
// row (avatar + timestamp-aware salutation), a gradient hero card with
// the primary CTA, and a richer insights dashboard that prepends a
// severity ring to the stat cells. Everything is built from data that
// already exists on `SymptomLog` and `HomeInsights` — no backend changes.
// ──────────────────────────────────────────────────────────────────────

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
        containerColor = MaterialTheme.colorScheme.background,
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
            GreetingRow()
            HeroCard(logCount = logs.size, onLogSymptomClick = onLogSymptomClick)
            InsightsDashboard(insights = insights)
            QuickActionCard(
                title = "View history",
                subtitle = "Review and search your past logs",
                onClick = onViewHistoryClick,
            )
            ThemePickerCard(current = themeMode, onSelect = themeViewModel::setThemeMode)
            if (logs.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                logs.take(3).forEach { log -> RecentLogRow(log = log, onClick = { onOpenLog(log.id) }) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GreetingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "A",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Aura Health",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Your daily check-in",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun HeroCard(logCount: Int, onLogSymptomClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(auraHeroGradient())
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "THIS WEEK",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                )
                Text(
                    text = if (logCount == 0) "Start tracking\nyour health." else "$logCount ${if (logCount == 1) "entry" else "entries"}\nlogged",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    lineHeight = 34.sp,
                )
                Text(
                    "Log symptoms as they happen so patterns become easier to spot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onLogSymptomClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("btn_home_log"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Log a symptom", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("insights_dashboard"),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Insights",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "7 DAYS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val ringSeverity = insights.averageSeverity?.toInt()?.coerceIn(1, 10) ?: 0
                SeverityRing(
                    severity = ringSeverity,
                    modifier = Modifier.testTag("stat_avg_severity"),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatBlock(
                        label = "Total",
                        value = insights.totalLogs.toString(),
                        icon = Icons.Filled.BarChart,
                        testTag = "stat_total",
                    )
                    StatBlock(
                        label = "Top symptom",
                        value = insights.mostCommonSymptom ?: "—",
                        helper = insights.mostCommonSymptom?.let { "${insights.mostCommonSymptomCount}×" },
                        icon = Icons.Filled.MonitorHeart,
                        testTag = "stat_top_symptom",
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LAST 7 DAYS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val total = insights.trend.sumOf { it.count }
                    Text(
                        "$total ${if (total == 1) "log" else "logs"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = AuraMonoFamily,
                    )
                }
                TrendChart(
                    trend = insights.trend,
                    peak = insights.trendPeak,
                    barBrush = Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        ),
                    ),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .testTag("trend_chart"),
                )
                TrendLabels(insights.trend)
            }
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    helper: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (helper != null) {
            Text(
                helper,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentLogRow(log: SymptomLog, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("home_recent_${log.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 14.dp, end = 14.dp),
        ) {
            SeverityEdge(
                severity = log.severity,
                modifier = Modifier
                    .height(44.dp)
                    .padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    log.symptomName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    formatWhen(log.startEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SeverityPill(severity = log.severity)
            Spacer(Modifier.size(6.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RELATIVE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE · HH:mm", Locale.getDefault())

private fun formatWhen(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(RELATIVE_FORMATTER)

@Composable
private fun TrendChart(
    trend: List<DailyCount>,
    peak: Int,
    barBrush: Brush,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { 6.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val strokePx = with(density) { 1.5.dp.toPx() }
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
            if (entry.count == 0) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, topInset),
                    size = Size(barWidth, plotHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = strokePx),
                )
            } else {
                val heightFraction = entry.count.toFloat() / effectivePeak.toFloat()
                val barHeight = (plotHeight * heightFraction).coerceAtLeast(strokePx * 2)
                drawRoundRect(
                    brush = barBrush,
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

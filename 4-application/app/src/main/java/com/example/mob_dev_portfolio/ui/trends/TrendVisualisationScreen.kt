package com.example.mob_dev_portfolio.ui.trends

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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.InsertChartOutlined
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────
// Fullscreen Trends dashboard.
//
// Material 3 density guidance says: put the *controls* here, not on the
// home card. The home preview is intentionally chromeless (one symptom,
// one week, no overlays) so Home scans cleanly; a user who wants to
// change the picks taps through to this screen.
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendVisualisationScreen(
    onBack: () -> Unit,
    viewModel: TrendVisualisationViewModel = viewModel(factory = TrendVisualisationViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Trends") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_trends_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("trends_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderCopy()
            RangeSegmented(selected = state.range, onSelect = viewModel::setRange)
            WindowNav(
                range = state.range,
                windowStart = state.windowStart,
                windowEnd = state.windowEnd,
                canStepForward = state.canStepForward,
                onPrev = viewModel::stepBack,
                onNext = viewModel::stepForward,
                onToday = viewModel::resetToNow,
            )
            SymptomPicker(
                options = state.symptomOptions,
                selected = state.selectedSymptom,
                onSelect = viewModel::setSymptom,
            )
            OverlaysDropdown(
                options = state.overlayOptions,
                selectedIds = state.selectedOverlayIds,
                onToggle = viewModel::toggleOverlay,
                onClear = viewModel::clearOverlays,
            )
            ChartCard(state = state)
            LegendCard(series = state.series)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HeaderCopy() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Spot correlations",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Plot one symptom against environment & Health Connect signals to see what changes when you feel it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RangeSegmented(selected: TrendRange, onSelect: (TrendRange) -> Unit) {
    val options = TrendRange.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trends_range"),
    ) {
        options.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag("trends_range_${range.name.lowercase()}"),
                label = { Text(range.label) },
            )
        }
    }
}

/**
 * Prev / window-label / next row + an "Today" shortcut. Lets the user
 * page back through their history one range at a time — critical for
 * long-running journals where "last week" isn't the interesting week.
 */
@Composable
private fun WindowNav(
    range: TrendRange,
    windowStart: Instant,
    windowEnd: Instant,
    canStepForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val label = remember(range, windowStart, windowEnd) {
        formatWindow(range, windowStart, windowEnd, zone)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trends_window_nav"),
    ) {
        IconButton(
            onClick = onPrev,
            modifier = Modifier.testTag("trends_window_prev"),
        ) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous window")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = onNext,
            enabled = canStepForward,
            modifier = Modifier.testTag("trends_window_next"),
        ) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next window")
        }
        if (canStepForward) {
            AssistChip(
                onClick = onToday,
                label = { Text("Today") },
                modifier = Modifier.testTag("trends_window_today"),
            )
        }
    }
}

/** Formats a window label in the densest form that still disambiguates
 *  the range — just a date for Day, a day range for Week/Month, a month
 *  range for 6m/Year. */
private fun formatWindow(
    range: TrendRange,
    start: Instant,
    end: Instant,
    zone: ZoneId,
): String {
    val startZ = start.atZone(zone)
    // End is the exclusive bucket-after-last, so subtract a second to
    // get the human-readable "last full day in the window".
    val endZ = end.minusSeconds(1).atZone(zone)
    return when (range) {
        TrendRange.Day -> startZ.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))
        TrendRange.Week, TrendRange.Month -> {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val yearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
            "${startZ.format(fmt)} – ${endZ.format(yearFmt)}"
        }
        TrendRange.SixMonths, TrendRange.Year -> {
            val fmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
            "${startZ.format(fmt)} – ${endZ.format(fmt)}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymptomPicker(
    options: List<SymptomOption>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Symptom",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (options.isEmpty()) {
            // Placeholder box matches the dropdown's height so the
            // layout doesn't jump when a first log arrives.
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "No symptoms logged yet — add one from Home to start charting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selected.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Choose a symptom") },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                    )
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag("trends_symptom_picker"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(option.name, modifier = Modifier.weight(1f))
                                Text(
                                    "${option.logCount}×",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(option.name)
                            expanded = false
                        },
                        modifier = Modifier.testTag("trends_symptom_item_${option.name}"),
                    )
                }
            }
        }
    }
}

/**
 * Exposes `androidx.compose.material3.ExposedDropdownMenu` — the
 * direct import is experimental and dragging the `@OptIn` into every
 * call site makes the code noisy, so we wrap it once here and carry
 * the annotation on the wrapper.
 */
@Composable
private fun ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/**
 * Multi-select dropdown for overlay picks. Replaces the old FlowRow of
 * FilterChips — with Health Connect metrics the count can push past a
 * dozen, which wraps messily on a phone-width screen. Dropdown stays
 * compact and puts the label + current-count on a single button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlaysDropdown(
    options: List<OverlayOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Overlays",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = overlaySummary(options, selectedIds),
                onValueChange = {},
                readOnly = true,
                label = { Text("Add overlays") },
                trailingIcon = {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag("trends_overlays"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (selectedIds.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Clear all",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            onClear()
                        },
                        modifier = Modifier.testTag("trends_overlays_clear"),
                    )
                }
                options.forEach { option ->
                    val selected = option.id in selectedIds
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(option.label, modifier = Modifier.weight(1f))
                                Text(
                                    option.units,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onToggle(option.id) },
                        modifier = Modifier.testTag("trends_overlay_${option.id}"),
                    )
                }
            }
        }
    }
}

private fun overlaySummary(
    options: List<OverlayOption>,
    selectedIds: Set<String>,
): String {
    if (selectedIds.isEmpty()) return ""
    val labels = options.filter { it.id in selectedIds }.map { it.label }
    return when (labels.size) {
        0 -> ""
        1 -> labels.first()
        2 -> labels.joinToString(", ")
        else -> "${labels.first()} +${labels.size - 1} more"
    }
}

@Composable
private fun ChartCard(state: TrendUiState) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selectedSymptom ?: "Trend",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    state.range.label.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.symptomOptions.isEmpty()) {
                EmptyNoSymptomsRow()
            } else {
                val palette = state.series.indices.map { chartColor(it) }
                TrendLineChart(
                    series = state.series,
                    colors = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .testTag("trends_chart"),
                )
                AxisTimeLabels(state = state)
            }
        }
    }
}

@Composable
private fun EmptyNoSymptomsRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.InsertChartOutlined,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            "Log your first symptom from Home to unlock the chart.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AxisTimeLabels(state: TrendUiState) {
    if (state.series.isEmpty()) return
    val buckets = state.series.first().buckets
    if (buckets.isEmpty()) return
    val zone = remember { ZoneId.systemDefault() }
    val formatter = remember(state.range) {
        when (state.range) {
            TrendRange.Day -> DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            TrendRange.Week, TrendRange.Month -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            TrendRange.SixMonths, TrendRange.Year -> DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        }
    }
    val ticks = when (buckets.size) {
        0 -> return
        1 -> listOf(buckets.first())
        2 -> listOf(buckets.first(), buckets.last())
        3 -> buckets
        else -> listOf(buckets.first(), buckets[buckets.size / 2], buckets.last())
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ticks.forEach { bucket ->
            Text(
                Instant.ofEpochMilli(bucket.bucketStart.toEpochMilli())
                    .atZone(zone)
                    .format(formatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendCard(series: List<TrendSeries>) {
    if (series.isEmpty()) return
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
                "Legend",
                style = MaterialTheme.typography.titleMedium,
            )
            series.forEachIndexed { index, s ->
                LegendRow(series = s, color = chartColor(index))
            }
        }
    }
}

@Composable
private fun LegendRow(series: TrendSeries, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                series.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val rangeLine = when {
                series.rawMin != null && series.rawMax != null && series.rawMin != series.rawMax ->
                    "${formatRaw(series.rawMin)} – ${formatRaw(series.rawMax)} ${series.units}"
                series.rawMax != null -> "${formatRaw(series.rawMax)} ${series.units}"
                else -> "No data in this range"
            }
            Text(
                rangeLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistKindBadge(kind = series.seriesKind)
    }
}

@Composable
private fun AssistKindBadge(kind: TrendSeriesKind) {
    val (label, container) = when (kind) {
        TrendSeriesKind.Symptom -> "Symptom" to MaterialTheme.colorScheme.primaryContainer
        TrendSeriesKind.Environmental -> "Env" to MaterialTheme.colorScheme.secondaryContainer
        TrendSeriesKind.Health -> "Health" to MaterialTheme.colorScheme.tertiaryContainer
    }
    Box(
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when (kind) {
                TrendSeriesKind.Symptom -> MaterialTheme.colorScheme.onPrimaryContainer
                TrendSeriesKind.Environmental -> MaterialTheme.colorScheme.onSecondaryContainer
                TrendSeriesKind.Health -> MaterialTheme.colorScheme.onTertiaryContainer
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Per-series colour palette. Index 0 is always the symptom (primary),
 * then a rotation of theme accents for overlays. Picked so adjacent
 * lines stay distinguishable even when four or five are on screen —
 * using raw `primary/secondary/tertiary` alone runs out at three.
 */
@Composable
internal fun chartColor(index: Int): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
    )
    return palette[index % palette.size]
}

private fun formatRaw(value: Double): String {
    if (value >= 1000.0) return String.format(Locale.getDefault(), "%,.0f", value)
    if (value >= 10.0) return String.format(Locale.getDefault(), "%.0f", value)
    return String.format(Locale.getDefault(), "%.1f", value)
}

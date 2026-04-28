package com.example.mob_dev_portfolio.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.ui.components.SeverityEdge
import com.example.mob_dev_portfolio.ui.components.SeverityPill
import com.example.mob_dev_portfolio.ui.log.LogValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private val DateOnlyFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenLog: (Long) -> Unit,
    onAddSymptom: () -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val conditionGrouping by viewModel.conditionGrouping.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                // Renamed from "History" to "Symptoms" when the Log tab
                // was folded into this screen via the FAB — the user's
                // mental model is a single place for "my symptoms",
                // with "add one" and "browse past ones" both reachable
                // from here.
                title = { Text("Symptoms") },
                actions = {
                    BadgedBox(
                        badge = {
                            if (ui.filter.hasActiveFilters) {
                                Badge(modifier = Modifier.testTag("history_filter_badge"))
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        IconButton(
                            onClick = { showSheet = true },
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("btn_open_filters"),
                        ) {
                            Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Extended FAB (rather than a plain circular one) so the
            // "Log" affordance stays discoverable to new users who
            // might otherwise read the bottom bar and assume the
            // tab's only purpose is browsing past entries. Collapses
            // to an icon-only FAB in contexts that demand it via
            // Material's expanded/collapsed handling.
            ExtendedFloatingActionButton(
                onClick = onAddSymptom,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Log") },
                modifier = Modifier.testTag("btn_symptoms_add"),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchBar(
                query = ui.filter.query,
                onQueryChange = viewModel::onQueryChange,
                onClear = { viewModel.onQueryChange("") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (ui.filter.hasActiveFilters) {
                ActiveFilterChipRow(
                    filter = ui.filter,
                    onClearQuery = { viewModel.onQueryChange("") },
                    onClearSeverity = {
                        viewModel.onSeverityRangeChange(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY)
                    },
                    onClearDateRange = { viewModel.onDateRangeChange(null, null) },
                    onRemoveTag = viewModel::onToggleTag,
                    onClearAll = viewModel::onClearFilters,
                )
            }

            if (logs.isEmpty()) {
                if (ui.filter.hasActiveFilters) {
                    NoMatchesState(
                        onClearFilters = viewModel::onClearFilters,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EmptyState(modifier = Modifier.fillMaxSize())
                }
            } else {
                // Group logs into a three-tier hierarchy:
                //   1. Ongoing symptoms (no end time) — top-of-mind so the
                //      user remembers to mark them ended. Cuts across
                //      condition groups by design.
                //   2. Logs grouped under each user-declared health
                //      condition (e.g. "Type 2 Diabetes"). One section
                //      per condition that has at least one ended log
                //      pinned to it.
                //   3. "Ended symptoms" catch-all for logs without a
                //      condition link.
                // Within each section the DAO's COALESCE(end, start)
                // ordering still applies.
                val (ongoingLogs, endedLogs) = remember(logs) {
                    logs.partition { it.endEpochMillis == null }
                }
                val groupedEndedLogs = remember(endedLogs, conditionGrouping) {
                    val byCondition = mutableMapOf<Long, MutableList<SymptomLog>>()
                    val ungrouped = mutableListOf<SymptomLog>()
                    endedLogs.forEach { log ->
                        val c = conditionGrouping.byLogId[log.id]
                        if (c == null) {
                            ungrouped += log
                        } else {
                            byCondition.getOrPut(c.id) { mutableListOf() } += log
                        }
                    }
                    // Stable ordering by condition list (newest-first via
                    // the repo's `observeAll` ordering) so the sections
                    // don't jitter as logs flow in.
                    val ordered = conditionGrouping.conditions.mapNotNull { c ->
                        byCondition[c.id]?.let { c to it.toList() }
                    }
                    Triple(ordered, ungrouped.toList(), byCondition.size)
                }
                val (conditionSections, ungroupedEnded, _) = groupedEndedLogs
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("history_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (ongoingLogs.isNotEmpty()) {
                        item(key = "header_ongoing") {
                            SectionHeader(
                                title = "Ongoing symptoms",
                                count = ongoingLogs.size,
                                tag = "history_section_ongoing",
                            )
                        }
                        items(items = ongoingLogs, key = { "ongoing_${it.id}" }) { log ->
                            LogCard(log = log, onClick = { onOpenLog(log.id) })
                        }
                    }
                    conditionSections.forEach { (condition, sectionLogs) ->
                        item(key = "header_cond_${condition.id}") {
                            SectionHeader(
                                title = condition.name,
                                count = sectionLogs.size,
                                tag = "history_section_condition_${condition.id}",
                            )
                        }
                        items(items = sectionLogs, key = { "cond_${condition.id}_${it.id}" }) { log ->
                            LogCard(log = log, onClick = { onOpenLog(log.id) })
                        }
                    }
                    if (ungroupedEnded.isNotEmpty()) {
                        item(key = "header_ended") {
                            // Title shifts subtly when the user has
                            // condition groupings: "Other ended symptoms"
                            // makes more sense than the bare "Ended
                            // symptoms" header in that context.
                            val title = if (conditionSections.isEmpty()) {
                                "Ended symptoms"
                            } else {
                                "Other ended symptoms"
                            }
                            SectionHeader(
                                title = title,
                                count = ungroupedEnded.size,
                                tag = "history_section_ended",
                            )
                        }
                        items(items = ungroupedEnded, key = { "ended_${it.id}" }) { log ->
                            LogCard(log = log, onClick = { onOpenLog(log.id) })
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        FilterSheet(
            filter = ui.filter,
            availableTags = ui.availableTags,
            onQueryChange = viewModel::onQueryChange,
            onSeverityRangeChange = viewModel::onSeverityRangeChange,
            onDateRangeChange = viewModel::onDateRangeChange,
            onToggleTag = viewModel::onToggleTag,
            onSortChange = viewModel::onSortChange,
            onClearAll = viewModel::onClearFilters,
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * Section header for the Ongoing / Ended split in the history list.
 *
 * Lives inline as `item { SectionHeader(...) }` rather than `stickyHeader`
 * because the user isn't scrolling so far that they'd lose context — and
 * a sticky version overlapping the back-pressing scrim of the filter
 * sheet leaks a visual artefact through. A simple item is the right
 * call.
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        // Count chip so the user instantly sees how many ongoing
        // symptoms still need stopping.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_search_clear"),
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        placeholder = { Text("Search logs") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.testTag("history_search"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterChipRow(
    filter: HistoryFilter,
    onClearQuery: () -> Unit,
    onClearSeverity: () -> Unit,
    onClearDateRange: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .testTag("history_active_filters"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (filter.hasQuery) {
            DismissibleChip(label = "\"${filter.query}\"", onRemove = onClearQuery, tag = "chip_active_query")
        }
        if (filter.hasSeverityBound) {
            DismissibleChip(
                label = "Severity ${filter.minSeverity}–${filter.maxSeverity}",
                onRemove = onClearSeverity,
                tag = "chip_active_severity",
            )
        }
        if (filter.hasDateBound) {
            val after = filter.startAfterEpochMillis?.let { epochToLocalDate(it).format(DateOnlyFormat) }
            val before = filter.startBeforeEpochMillis?.let { epochToLocalDate(it).format(DateOnlyFormat) }
            val label = when {
                after != null && before != null -> "$after – $before"
                after != null -> "After $after"
                before != null -> "Before $before"
                else -> "Date"
            }
            DismissibleChip(label = label, onRemove = onClearDateRange, tag = "chip_active_date")
        }
        filter.tags.forEach { tag ->
            DismissibleChip(label = tag, onRemove = { onRemoveTag(tag) }, tag = "chip_active_tag_$tag")
        }
        TextButton(
            onClick = onClearAll,
            modifier = Modifier.testTag("btn_clear_all_filters"),
        ) {
            Text("Clear all")
        }
    }
}

@Composable
private fun DismissibleChip(label: String, onRemove: () -> Unit, tag: String) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove filter", modifier = Modifier.size(16.dp)) },
        modifier = Modifier.testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: HistoryFilter,
    availableTags: List<String>,
    onQueryChange: (String) -> Unit,
    onSeverityRangeChange: (Int, Int) -> Unit,
    onDateRangeChange: (Long?, Long?) -> Unit,
    onToggleTag: (String) -> Unit,
    onSortChange: (HistorySort) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickAfter by remember { mutableStateOf(false) }
    var pickBefore by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("filter_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag("btn_sheet_clear"),
                ) { Text("Clear") }
            }

            FilterSection(title = "Search") {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Symptom, notes, medication…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("sheet_query"),
                )
            }

            FilterSection(title = "Severity") {
                SeverityRangeControl(
                    min = filter.minSeverity,
                    max = filter.maxSeverity,
                    onChange = onSeverityRangeChange,
                )
            }

            FilterSection(title = "Date range") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateRangeButton(
                            label = "From",
                            epochMillis = filter.startAfterEpochMillis,
                            onClick = { pickAfter = true },
                            onClear = { onDateRangeChange(null, filter.startBeforeEpochMillis) },
                            tag = "btn_date_from",
                        )
                        DateRangeButton(
                            label = "To",
                            epochMillis = filter.startBeforeEpochMillis,
                            onClick = { pickBefore = true },
                            onClear = { onDateRangeChange(filter.startAfterEpochMillis, null) },
                            tag = "btn_date_to",
                        )
                    }
                }
            }

            if (availableTags.isNotEmpty()) {
                FilterSection(title = "Context tags") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableTags.forEach { tag ->
                            FilterChip(
                                selected = filter.tags.contains(tag),
                                onClick = { onToggleTag(tag) },
                                label = { Text(tag) },
                                modifier = Modifier.testTag("chip_tag_$tag"),
                            )
                        }
                    }
                }
            }

            FilterSection(title = "Sort") {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    HistorySort.entries.forEach { sort ->
                        SortRow(
                            label = sort.label,
                            selected = filter.sort == sort,
                            onSelect = { onSortChange(sort) },
                            tag = "sort_${sort.name}",
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    if (pickAfter) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = filter.startAfterEpochMillis,
        )
        DatePickerDialog(
            onDismissRequest = { pickAfter = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            onDateRangeChange(startOfLocalDayUtc(millis), filter.startBeforeEpochMillis)
                        }
                        pickAfter = false
                    },
                    modifier = Modifier.testTag("btn_confirm_from"),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickAfter = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (pickBefore) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = filter.startBeforeEpochMillis,
        )
        DatePickerDialog(
            onDismissRequest = { pickBefore = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            onDateRangeChange(filter.startAfterEpochMillis, endOfLocalDayUtc(millis))
                        }
                        pickBefore = false
                    },
                    modifier = Modifier.testTag("btn_confirm_to"),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickBefore = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun SortRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String,
) {
    Surface(
        onClick = onSelect,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
private fun SeverityRangeControl(
    min: Int,
    max: Int,
    onChange: (Int, Int) -> Unit,
) {
    var range by remember(min, max) {
        mutableStateOf(min.toFloat()..max.toFloat())
    }
    Column {
        Text(
            "${range.start.toInt()} – ${range.endInclusive.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        RangeSlider(
            value = range,
            onValueChange = { new -> range = new },
            onValueChangeFinished = {
                onChange(range.start.toInt(), range.endInclusive.toInt())
            },
            valueRange = LogValidator.MIN_SEVERITY.toFloat()..LogValidator.MAX_SEVERITY.toFloat(),
            steps = LogValidator.MAX_SEVERITY - LogValidator.MIN_SEVERITY - 1,
            modifier = Modifier.testTag("severity_range"),
        )
    }
}

@Composable
private fun DateRangeButton(
    label: String,
    epochMillis: Long?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    tag: String,
) {
    val text = epochMillis?.let { epochToLocalDate(it).format(DateOnlyFormat) } ?: "Any"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            if (epochMillis != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp),
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear $label", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("history_empty"),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(112.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.AutoMirrored.Filled.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "No logs yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Symptoms you log will appear here so you can spot patterns over time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NoMatchesState(onClearFilters: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("history_no_matches"),
        ) {
            Text(
                "No logs match these filters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Try loosening the filters or clearing them to see everything.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(
                onClick = onClearFilters,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("btn_no_matches_clear"),
            ) { Text("Clear filters") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogCard(log: SymptomLog, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(log.startEpochMillis).atZone(zone).toLocalDateTime()
    // Surface (not Card) so the severity edge can sit flush on the left
    // without fighting the Card's internal padding and elevation shadow.
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("history_row_${log.id}"),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            SeverityEdge(
                severity = log.severity,
                modifier = Modifier
                    .width(4.dp)
                    .padding(vertical = 10.dp)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(log.symptomName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            start.format(DateTimeFormat),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily,
                        )
                    }
                    SeverityPill(severity = log.severity)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.description.isNotBlank()) {
                    Text(
                        log.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.contextTags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        log.contextTags.forEach { tag ->
                            TagChip(tag)
                        }
                    }
                }
                if (log.medication.isNotBlank()) {
                    Text(
                        "Medication: ${log.medication}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Compact read-only tag chip. Used inline on history cards. */
@Composable
private fun TagChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun epochToLocalDate(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun startOfLocalDayUtc(epochMillisUtcMidnight: Long): Long {
    val date = Instant.ofEpochMilli(epochMillisUtcMidnight).atZone(ZoneId.of("UTC")).toLocalDate()
    return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun endOfLocalDayUtc(epochMillisUtcMidnight: Long): Long {
    val date = Instant.ofEpochMilli(epochMillisUtcMidnight).atZone(ZoneId.of("UTC")).toLocalDate()
    return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}

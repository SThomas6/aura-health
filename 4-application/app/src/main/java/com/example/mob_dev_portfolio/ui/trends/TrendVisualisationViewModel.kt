package com.example.mob_dev_portfolio.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.environment.EnvironmentalHistoryRepository
import com.example.mob_dev_portfolio.data.environment.EnvironmentalHistoryService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalSample
import com.example.mob_dev_portfolio.data.environment.HistoryResult
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.data.health.HealthPreferencesRepository
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.ui.log.LogValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// ──────────────────────────────────────────────────────────────────────
// Trend Visualisation — data layer.
//
// The dashboard lets the user pick ONE symptom and OVERLAY any number of
// environmental / Health Connect metrics onto the same line graph, so
// they can eyeball whether (say) their migraines spike on low-pressure,
// low-sleep days.
//
// Series are bucketed on the same time axis — hourly for Day, daily for
// Week / Month, weekly for 6m / 1y — and each series is normalised to its
// own [0..1] range so radically different units (severity 1-10, steps
// 0-20000, hPa ~1000) can co-exist on one y-axis. The legend restores
// the real min/max so the user can read back what the normalised line
// actually means.
//
// The anchor instant ([anchorEnd]) lets the user scroll back through
// history — prev/next controls on the screen shift it by one range and
// every series refetches against the new window. "Today" resets it.
// ──────────────────────────────────────────────────────────────────────

/**
 * Unit of a single x-axis bucket. Kept separate from [TrendRange] so
 * the axis builder and bucketer can pattern-match on it (months have
 * variable real-time widths, so we can't just reduce everything to a
 * fixed seconds-per-bucket value).
 */
enum class BucketUnit { Hour, Day, Week, Month }

/**
 * Five fixed windows the user can pick between, mapped onto the bucket
 * sizing already used by [HealthHistoryRepository].
 *
 * Bucket unit chosen per range so the chart has ~7–12 plotted points:
 *  - Day → hourly (24 points)
 *  - Week → daily (7 points)
 *  - Month → weekly (~4 points, each the week's average)
 *  - 6 Months / Year → monthly (6 / 12 points, each the month's average)
 *
 * Anything more granular turns every line into noise on a phone-width
 * canvas; anything coarser loses the trend.
 */
enum class TrendRange(
    val label: String,
    val days: Int,
    val healthRange: HealthHistoryRepository.Range,
    val bucketUnit: BucketUnit,
) {
    Day("1d", 1, HealthHistoryRepository.Range.Day, BucketUnit.Hour),
    Week("1w", 7, HealthHistoryRepository.Range.Week, BucketUnit.Day),
    Month("1m", 30, HealthHistoryRepository.Range.Month, BucketUnit.Week),
    SixMonths("6m", 180, HealthHistoryRepository.Range.HalfYear, BucketUnit.Month),
    Year("1y", 365, HealthHistoryRepository.Range.Year, BucketUnit.Month);

    /**
     * Approximate bucket width in seconds. Used only for fuzzy
     * comparisons (HC nearest-neighbour matching) — axis layout walks
     * calendar units via [BucketUnit], so monthly buckets don't suffer
     * from the 30-day approximation drift at year boundaries.
     */
    val approxBucketSeconds: Long get() = when (bucketUnit) {
        BucketUnit.Hour -> 3_600L
        BucketUnit.Day -> 86_400L
        BucketUnit.Week -> 604_800L
        BucketUnit.Month -> 2_592_000L
    }

    companion object {
        // Week is the punchiest view — one week of daily buckets lets a
        // user eyeball trends without the "where's the line?" confusion
        // a six-month view creates on a fresh install.
        val Default: TrendRange = Week
    }
}

/**
 * A single selectable overlay. Models environmental columns (now served
 * as continuous time-series from [EnvironmentalHistoryRepository]) and
 * Health Connect metrics (read from HC at render time) uniformly — the
 * screen only sees an id / label / units, and the extraction path is
 * owned by the ViewModel.
 */
data class OverlayOption(
    val id: String,
    val label: String,
    val units: String,
    val kind: OverlayKind,
) {
    /** Health Connect option tied to a real metric record. Null for env options. */
    val healthMetric: HealthConnectMetric? = (kind as? OverlayKind.Health)?.metric
}

sealed class OverlayKind {
    data object EnvHumidity : OverlayKind()
    data object EnvTemperature : OverlayKind()
    data object EnvPressure : OverlayKind()
    data object EnvAqi : OverlayKind()
    data class Health(val metric: HealthConnectMetric) : OverlayKind()
}

/** The primary "what symptom am I charting" selection. The string is
 *  the symptom name — matched case-insensitively in the symptom log
 *  table so minor casing drift doesn't split a series in two. */
data class SymptomOption(val name: String, val logCount: Int)

/** One bucket on the x-axis. [rawValue] is null when the bucket had no
 *  reading — the chart can render it as a gap or fall back to zero per
 *  series kind. */
data class TrendBucket(
    val bucketStart: Instant,
    val rawValue: Double?,
)

/**
 * A complete series ready to draw: buckets (share the time axis), plus
 * the min/max of the raw values so the legend can label what "full
 * height" and "zero" mean on the normalised chart.
 */
data class TrendSeries(
    val id: String,
    val label: String,
    val units: String,
    val seriesKind: TrendSeriesKind,
    val buckets: List<TrendBucket>,
    val rawMin: Double?,
    val rawMax: Double?,
) {
    val hasData: Boolean get() = buckets.any { it.rawValue != null }
}

enum class TrendSeriesKind { Symptom, Environmental, Health }

/** Snapshot the screen renders from. */
data class TrendUiState(
    val range: TrendRange = TrendRange.Default,
    val symptomOptions: List<SymptomOption> = emptyList(),
    val selectedSymptom: String? = null,
    val overlayOptions: List<OverlayOption> = emptyList(),
    val selectedOverlayIds: Set<String> = emptySet(),
    val series: List<TrendSeries> = emptyList(),
    val isLoading: Boolean = true,
    val totalBuckets: Int = 0,
    /** End of the window the chart currently covers. Moves when the
     *  user presses prev/next; resets to "now" on range change or
     *  explicit reset. */
    val windowEnd: Instant = Instant.EPOCH,
    /** Start of the visible window (windowEnd − range.days). Handy for
     *  the nav row's date label so the UI doesn't recompute it. */
    val windowStart: Instant = Instant.EPOCH,
    /** Next-page navigation is only meaningful when the current window
     *  doesn't already end at "now" — we never let the user scroll
     *  forward into the future. */
    val canStepForward: Boolean = false,
)

/**
 * Single ViewModel for both the Home preview card and the fullscreen
 * dashboard. The preview doesn't expose range / overlay / symptom
 * controls, so it just inherits the default (Week, most-frequent
 * symptom, no overlays); the fullscreen route drives the same
 * underlying MutableStateFlows through the `setX` methods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendVisualisationViewModel(
    private val symptomRepository: SymptomLogRepository,
    private val healthHistoryRepository: HealthHistoryRepository,
    private val healthPreferences: HealthPreferencesRepository,
    private val environmentalHistoryRepository: EnvironmentalHistoryRepository? = null,
    private val uiPreferencesRepository: UiPreferencesRepository? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val selectedRange = MutableStateFlow(TrendRange.Default)
    private val selectedSymptom = MutableStateFlow<String?>(null)
    private val selectedOverlayIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * End of the currently-visible window. Moves by one range when the
     * user presses prev/next. Capped at `clock.instant()` — we never
     * scroll into the future, which keeps next-button semantics simple
     * (it's disabled once we're back at "now").
     */
    private val anchorEnd = MutableStateFlow(clock.instant())

    val range: StateFlow<TrendRange> = selectedRange.asStateFlow()
    val selection: StateFlow<String?> = selectedSymptom.asStateFlow()
    val overlays: StateFlow<Set<String>> = selectedOverlayIds.asStateFlow()

    private val logsFlow: Flow<List<SymptomLog>> = symptomRepository.observeAll()

    private val symptomOptionsFlow: Flow<List<SymptomOption>> = logsFlow.map { logs ->
        logs.groupingBy { it.symptomName }
            .eachCount()
            .map { (name, count) -> SymptomOption(name, count) }
            .sortedByDescending { it.logCount }
    }

    /**
     * The overlay list is the four environmental columns plus whichever
     * Health Connect metrics the user has opted in to. Excluding
     * non-opted ones means we don't prompt them to grant permissions
     * just for hovering over the Trends screen — they see the overlays
     * they themselves configured on the Health Data Settings screen.
     */
    private val overlayOptionsFlow: Flow<List<OverlayOption>> = healthPreferences.enabledMetrics.map { enabled ->
        val env = listOf(
            OverlayOption("env_humidity", "Humidity", "%", OverlayKind.EnvHumidity),
            OverlayOption("env_temperature", "Temperature", "°C", OverlayKind.EnvTemperature),
            OverlayOption("env_pressure", "Pressure", "hPa", OverlayKind.EnvPressure),
            OverlayOption("env_aqi", "Air quality", "AQI", OverlayKind.EnvAqi),
        )
        // Only metrics the repository can graph — Height is static
        // and makes no sense as a time-series overlay, but we keep it
        // filtered out here rather than in the repo so settings still
        // offers it for the AI prompt.
        val graphableHealth = enabled
            .filter { it != HealthConnectMetric.Height }
            .map { metric ->
                OverlayOption(
                    id = "hc_${metric.storageKey}",
                    label = metric.displayLabel,
                    units = unitsFor(metric),
                    kind = OverlayKind.Health(metric),
                )
            }
            .sortedBy { it.label }
        env + graphableHealth
    }

    /** Fullscreen state — driven by in-memory controls (range,
     *  symptom, multi-select overlays, nav anchor). */
    val state: StateFlow<TrendUiState> = buildStateFlow(
        rangeFlow = selectedRange,
        overlayIdsFlow = selectedOverlayIds,
        anchorFlow = anchorEnd,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        TrendUiState(
            windowEnd = clock.instant(),
            windowStart = clock.instant().minus(Duration.ofDays(TrendRange.Default.days.toLong())),
        ),
    )

    /**
     * Home preview state — always Week-ranged, anchored to now, and the
     * overlay is the *single* most-recent pick from the persisted
     * [UiPreferencesRepository.lastTrendOverlayId]. If the user has
     * never toggled an overlay, we default to [DEFAULT_HOME_OVERLAY_ID]
     * (sleep) on the theory that sleep is the "most common" signal
     * people want to correlate. The id isn't always resolvable — Sleep
     * only shows up as an overlay option once HC is enabled — so
     * anything the repository can't map back to a real option is
     * silently dropped and the card renders the symptom line on its
     * own.
     */
    val homePreviewState: StateFlow<TrendUiState> = buildStateFlow(
        rangeFlow = flowOf(TrendRange.Default),
        overlayIdsFlow = homeOverlayIdsFlow(),
        anchorFlow = flowOf(clock.instant()),
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        TrendUiState(
            windowEnd = clock.instant(),
            windowStart = clock.instant().minus(Duration.ofDays(TrendRange.Default.days.toLong())),
        ),
    )

    private fun homeOverlayIdsFlow(): Flow<Set<String>> {
        val prefs = uiPreferencesRepository ?: return flowOf(setOf(DEFAULT_HOME_OVERLAY_ID))
        return prefs.lastTrendOverlayId.map { persisted ->
            // Wrap the single id in a one-element set so the rest of
            // the pipeline (which treats overlays as a set) doesn't
            // need a special case for "exactly one".
            setOf(persisted ?: DEFAULT_HOME_OVERLAY_ID)
        }
    }

    private fun buildStateFlow(
        rangeFlow: Flow<TrendRange>,
        overlayIdsFlow: Flow<Set<String>>,
        anchorFlow: Flow<Instant>,
    ): Flow<TrendUiState> = combine(
        rangeFlow,
        selectedSymptom,
        overlayIdsFlow,
        anchorFlow,
    ) { range, symptom, overlayIds, anchor -> Quadruple(range, symptom, overlayIds, anchor) }
        .flatMapLatest { (range, symptom, overlayIds, anchor) ->
            combine(
                logsFlow,
                symptomOptionsFlow,
                overlayOptionsFlow,
            ) { logs, symptomOptions, overlayOptions ->
                // Resolve the effective symptom selection: if the user
                // hasn't picked one yet (or the one they picked is no
                // longer in the log table), fall back to the most
                // frequent. That gives the Home preview something to
                // render on a fresh install as soon as one log exists.
                val effectiveSymptom = symptom
                    ?.takeIf { sel -> symptomOptions.any { it.name.equals(sel, ignoreCase = true) } }
                    ?: symptomOptions.firstOrNull()?.name
                RenderInputs(logs, symptomOptions, overlayOptions, effectiveSymptom, range, overlayIds, anchor)
            }.flatMapLatest { inputs ->
                flow {
                    val axis = buildAxis(inputs.range, inputs.anchor)
                    val windowStart = axis.bucketStarts.firstOrNull() ?: inputs.anchor
                    val windowEnd = axis.bucketEnds.lastOrNull() ?: inputs.anchor
                    val canStepForward = inputs.anchor.isBefore(clock.instant().minusSeconds(60))

                    // --- First emit: symptom series only (synchronous),
                    // loading=true so the screen shows a spinner/ghost
                    // for the overlays. This keeps TTI snappy even when
                    // the network call behind env-history is cold.
                    val symptomSeries = inputs.effectiveSymptom?.let { name ->
                        buildSymptomSeries(inputs.logs, name, axis)
                    }
                    emit(
                        TrendUiState(
                            range = inputs.range,
                            symptomOptions = inputs.symptomOptions,
                            selectedSymptom = inputs.effectiveSymptom,
                            overlayOptions = inputs.overlayOptions,
                            selectedOverlayIds = inputs.overlayIds,
                            series = listOfNotNull(symptomSeries),
                            totalBuckets = axis.size,
                            isLoading = true,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            canStepForward = canStepForward,
                        ),
                    )

                    // --- Env history: one network call for this window.
                    // Repository caches, so range-toggling and overlay-
                    // toggling between already-seen windows is instant.
                    val selectedEnvOptions = inputs.overlayOptions
                        .filter { it.id in inputs.overlayIds }
                        .filter { it.kind !is OverlayKind.Health }
                    val envSamples: List<EnvironmentalSample> = if (selectedEnvOptions.isEmpty()) {
                        emptyList()
                    } else {
                        val repo = environmentalHistoryRepository
                        if (repo == null) {
                            emptyList()
                        } else {
                            val granularity = granularityFor(inputs.range)
                            val result = runCatching {
                                repo.fetchHistory(windowStart, windowEnd, granularity)
                            }.getOrElse { HistoryResult.Unavailable }
                            when (result) {
                                is HistoryResult.Success -> result.samples
                                HistoryResult.Unavailable -> emptyList()
                            }
                        }
                    }
                    val envSeries = selectedEnvOptions.map { option ->
                        buildEnvSeriesFromSamples(envSamples, option, axis)
                    }

                    emit(
                        TrendUiState(
                            range = inputs.range,
                            symptomOptions = inputs.symptomOptions,
                            selectedSymptom = inputs.effectiveSymptom,
                            overlayOptions = inputs.overlayOptions,
                            selectedOverlayIds = inputs.overlayIds,
                            series = listOfNotNull(symptomSeries) + envSeries,
                            totalBuckets = axis.size,
                            isLoading = true,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            canStepForward = canStepForward,
                        ),
                    )

                    // --- HC reads: suspend fns, read sequentially so we
                    // don't spam the provider with concurrent record
                    // requests. A 4-metric 1-year read comes back in
                    // well under a second on sample data.
                    val healthSeries = inputs.overlayOptions
                        .filter { it.id in inputs.overlayIds }
                        .mapNotNull { option -> (option.kind as? OverlayKind.Health)?.metric?.to(option) }
                        .map { (metric, option) -> buildHealthSeries(metric, option, inputs.range, axis, inputs.anchor) }
                    emit(
                        TrendUiState(
                            range = inputs.range,
                            symptomOptions = inputs.symptomOptions,
                            selectedSymptom = inputs.effectiveSymptom,
                            overlayOptions = inputs.overlayOptions,
                            selectedOverlayIds = inputs.overlayIds,
                            series = listOfNotNull(symptomSeries) + envSeries + healthSeries,
                            totalBuckets = axis.size,
                            isLoading = false,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            canStepForward = canStepForward,
                        ),
                    )
                }
            }
        }

    fun setRange(next: TrendRange) {
        selectedRange.update { next }
        // A new range implies a fresh "up to now" window — a user
        // switching from 1y (anchored a month back) to 1w expects the
        // last seven days, not a disconnected historical slice.
        anchorEnd.update { clock.instant() }
    }

    fun setSymptom(name: String) { selectedSymptom.update { name } }

    fun toggleOverlay(id: String) {
        var didAdd = false
        selectedOverlayIds.update { current ->
            if (id in current) {
                current - id
            } else {
                didAdd = true
                current + id
            }
        }
        // Only writes on *add* — the home preview's "most recently
        // selected" semantics mean we don't want an untoggle to promote
        // some older pick. If the user clears everything in the
        // fullscreen the home card keeps its last pick, which matches
        // how history/filter prefs behave elsewhere in the app.
        if (didAdd) {
            uiPreferencesRepository?.let { repo ->
                viewModelScope.launch { repo.setLastTrendOverlayId(id) }
            }
        }
    }

    fun clearOverlays() { selectedOverlayIds.update { emptySet() } }

    /**
     * Walk the window one range backwards (e.g. Week view goes from
     * "last 7 days" to "8–14 days ago"). The env history cache is
     * keyed on the exact (start, end) pair, so returning to a
     * previously-visited window is instant.
     */
    fun stepBack() {
        val r = selectedRange.value
        anchorEnd.update { current -> current.minus(Duration.ofDays(r.days.toLong())) }
    }

    /** Walk forwards by one range, but never past "now". */
    fun stepForward() {
        val r = selectedRange.value
        anchorEnd.update { current ->
            val next = current.plus(Duration.ofDays(r.days.toLong()))
            val now = clock.instant()
            if (next.isAfter(now)) now else next
        }
    }

    fun resetToNow() { anchorEnd.update { clock.instant() } }

    // ── Series construction ───────────────────────────────────────────

    /**
     * Pre-computed axis for a render pass. We store each bucket's
     * [bucketStarts] and [bucketEnds] separately rather than a single
     * "bucket size" so the monthly granularity (where a bucket is
     * 28–31 days long) falls out naturally instead of needing special
     * cases in every downstream consumer.
     */
    private data class Axis(
        val bucketStarts: List<Instant>,
        val bucketEnds: List<Instant>,
        val unit: BucketUnit,
    ) {
        val size: Int get() = bucketStarts.size
    }

    /**
     * Walk calendar units from the aligned window start up to
     * [endInstant], emitting one bucket per unit. Starts + ends are
     * paired 1:1 so the bucketer can do an O(log N) binary search for
     * the bucket any sample falls into.
     */
    private fun buildAxis(range: TrendRange, endInstant: Instant): Axis {
        val rawStart = endInstant.minus(Duration.ofDays(range.days.toLong()))
        val alignedStart = alignDown(rawStart, range.bucketUnit)
        val starts = mutableListOf<Instant>()
        val ends = mutableListOf<Instant>()
        var cursor = alignedStart
        // Safety cap — a monthly 1-year view is 13 buckets, a daily 1-month
        // view is 31, an hourly 1-day view is 25. 512 is comfortably larger
        // than anything legitimate and bounds a runaway loop from e.g. a
        // broken advance() returning the same instant twice.
        var guard = 0
        while (!cursor.isAfter(endInstant) && guard < 512) {
            val next = advance(cursor, range.bucketUnit)
            starts += cursor
            ends += next
            cursor = next
            guard++
        }
        if (starts.isEmpty()) {
            // Extreme edge case (e.g. endInstant == alignedStart and unit
            // makes advance strictly forward): emit at least one bucket so
            // downstream code can render an empty chart rather than NPE.
            starts += alignedStart
            ends += advance(alignedStart, range.bucketUnit)
        }
        return Axis(starts, ends, range.bucketUnit)
    }

    private fun alignDown(instant: Instant, unit: BucketUnit): Instant {
        val zoned = instant.atZone(zone)
        return when (unit) {
            BucketUnit.Hour -> zoned.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant()
            BucketUnit.Day -> zoned.toLocalDate().atStartOfDay(zone).toInstant()
            BucketUnit.Week -> {
                // ISO week: Monday-start. Align to this week's Monday.
                val dow = zoned.dayOfWeek.value - 1
                zoned.toLocalDate().minusDays(dow.toLong()).atStartOfDay(zone).toInstant()
            }
            BucketUnit.Month -> zoned.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(zone)
                .toInstant()
        }
    }

    /** Advance by exactly one [unit] starting from [instant]. */
    private fun advance(instant: Instant, unit: BucketUnit): Instant {
        val zoned = instant.atZone(zone)
        return when (unit) {
            BucketUnit.Hour -> instant.plusSeconds(3_600L)
            BucketUnit.Day -> zoned.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            BucketUnit.Week -> zoned.toLocalDate().plusWeeks(1).atStartOfDay(zone).toInstant()
            BucketUnit.Month -> zoned.toLocalDate().plusMonths(1).atStartOfDay(zone).toInstant()
        }
    }

    /**
     * Build the symptom series. If a log has an [SymptomLog.endEpochMillis],
     * treat it as spanning start..end and emit one sample per bucket
     * step inside the span — the chart then shows one point per day for
     * a multi-day episode instead of a single mark on the start day.
     */
    private fun buildSymptomSeries(
        logs: List<SymptomLog>,
        symptomName: String,
        axis: Axis,
    ): TrendSeries {
        val filtered = logs.filter { it.symptomName.equals(symptomName, ignoreCase = true) }
        // Always step multi-day expansions at day resolution — this
        // guarantees that every bucket a span crosses receives at least
        // one sample, regardless of whether the axis is hourly, daily,
        // weekly, or monthly. The bucketer then averages the duplicates
        // back into a single value per bucket.
        val stepMillis = 86_400_000L
        val axisStartMillis = axis.bucketStarts.firstOrNull()?.toEpochMilli() ?: Long.MIN_VALUE
        val axisEndMillis = axis.bucketEnds.lastOrNull()?.toEpochMilli() ?: Long.MAX_VALUE

        val records = buildList {
            filtered.forEach { log ->
                val sev = log.severity.toDouble()
                val startMs = log.startEpochMillis
                val endMs = log.endEpochMillis
                if (endMs == null || endMs <= startMs) {
                    // Single-instant log.
                    add(startMs to sev)
                } else {
                    // Multi-day log: step through at bucket resolution
                    // so a 4-day headache paints four points, not one.
                    // Clip to the visible axis to avoid generating
                    // thousands of points for a long-ago chronic log
                    // while viewing 1 Day.
                    val from = maxOf(startMs, axisStartMillis)
                    val to = minOf(endMs, axisEndMillis)
                    if (from <= to) {
                        var cursor = from
                        while (cursor <= to) {
                            add(cursor to sev)
                            cursor += stepMillis
                        }
                    } else {
                        // Span entirely outside the window; keep at
                        // least the start if it falls in-range (the
                        // bucketer drops out-of-range anyway).
                        add(startMs to sev)
                    }
                }
            }
        }

        // Fill empty buckets with zero so the symptom line is always
        // continuous — a user asked the chart to "link every point
        // even if it drops to zero" so the shape of their symptom
        // journey is visible at a glance. Overlays keep the null-as-
        // gap semantics because sparse env/HC samples genuinely mean
        // "we don't know", but for a symptom log absence *is* data
        // (no reading = not experienced that day) and zero is the
        // correct reading.
        val rawBuckets = bucketAverage(records, axis)
        val buckets = rawBuckets.map { b ->
            if (b.rawValue == null) TrendBucket(b.bucketStart, 0.0) else b
        }
        val values = filtered.map { it.severity.toDouble() }
        return TrendSeries(
            id = "symptom",
            label = symptomName,
            units = "severity",
            seriesKind = TrendSeriesKind.Symptom,
            buckets = buckets,
            // Anchor the y-axis to zero on the low end so the "no
            // symptom" days visibly dip to the chart's baseline rather
            // than sitting on the validator minimum of 1. Top still
            // follows the validator's upper bound so a mild (severity
            // 2) log doesn't render as a full-height spike.
            rawMin = 0.0,
            rawMax = values.maxOrNull()?.let { maxOf(it, LogValidator.MAX_SEVERITY.toDouble()) }
                ?: LogValidator.MAX_SEVERITY.toDouble(),
        )
    }

    /**
     * Bucket historical environmental samples onto the axis. Replaces
     * the old "read the column off each symptom log" behaviour — those
     * values were just a point-in-time capture per log; the dashboard
     * wants a continuous line across the window.
     */
    private fun buildEnvSeriesFromSamples(
        samples: List<EnvironmentalSample>,
        option: OverlayOption,
        axis: Axis,
    ): TrendSeries {
        val extractor: (EnvironmentalSample) -> Double? = when (option.kind) {
            OverlayKind.EnvHumidity -> { s -> s.humidityPercent?.toDouble() }
            OverlayKind.EnvTemperature -> { s -> s.temperatureCelsius }
            OverlayKind.EnvPressure -> { s -> s.pressureHpa }
            OverlayKind.EnvAqi -> { s -> s.airQualityIndex?.toDouble() }
            is OverlayKind.Health -> { _ -> null }
        }
        val tuples = samples.mapNotNull { s ->
            val v = extractor(s) ?: return@mapNotNull null
            s.time.toEpochMilli() to v
        }
        val buckets = bucketAverage(tuples, axis)
        val rawValues = tuples.map { it.second }
        return TrendSeries(
            id = option.id,
            label = option.label,
            units = option.units,
            seriesKind = TrendSeriesKind.Environmental,
            buckets = buckets,
            rawMin = rawValues.minOrNull(),
            rawMax = rawValues.maxOrNull(),
        )
    }

    private suspend fun buildHealthSeries(
        metric: HealthConnectMetric,
        option: OverlayOption,
        range: TrendRange,
        axis: Axis,
        endInstant: Instant,
    ): TrendSeries {
        val hc = healthHistoryRepository.readSeries(metric, range.healthRange, endInstant)
        // Route the HC repo's pre-bucketed points through our own
        // bucketAverage. HC may hand back weekly points (for 6m/1y)
        // while our axis is monthly, so we can't just look up by
        // `bucketStart` — the bucketer averages multiple HC points into
        // each trend bucket and emits null where HC has no coverage,
        // which matches the env path and keeps gaps honest.
        val tuples = hc.points.map { it.bucketStart.toEpochMilli() to it.value }
        val buckets = bucketAverage(tuples, axis)
        // Zero readings are meaningful for cumulative metrics (steps = 0
        // means the device wasn't worn / user didn't walk) so for the
        // raw-range (legend) calculation we only filter non-positive
        // values out to avoid squashing the chart min down to zero when
        // the user has a day of missing coverage.
        val rawValues = buckets.mapNotNull { it.rawValue }.filter { it > 0 }
        return TrendSeries(
            id = option.id,
            label = option.label,
            units = option.units,
            seriesKind = TrendSeriesKind.Health,
            buckets = buckets,
            rawMin = rawValues.minOrNull(),
            rawMax = rawValues.maxOrNull(),
        )
    }

    /**
     * Average the samples into the axis buckets. A bucket with no
     * samples emits a null raw value — the chart draws null as a gap
     * rather than a misleading zero.
     */
    /**
     * Average the samples into the axis buckets. A bucket with no
     * samples emits a null raw value — the chart draws null as a gap
     * rather than a misleading zero.
     *
     * Uses binary search against the bucket start/end arrays so the
     * variable-width monthly buckets (28–31 days) route samples
     * correctly. Fixed-width bucket sizes would still work with a plain
     * `(delta / stepMillis).toInt()`, but a single unified path is
     * easier to reason about than two divergent branches.
     */
    private fun bucketAverage(
        records: List<Pair<Long, Double>>,
        axis: Axis,
    ): List<TrendBucket> {
        if (axis.bucketStarts.isEmpty()) return emptyList()
        val startsMs = LongArray(axis.size) { axis.bucketStarts[it].toEpochMilli() }
        val endsMs = LongArray(axis.size) { axis.bucketEnds[it].toEpochMilli() }
        val sums = DoubleArray(axis.size)
        val counts = IntArray(axis.size)
        records.forEach { (time, value) ->
            // Find the bucket where startsMs[i] <= time < endsMs[i].
            var lo = 0
            var hi = axis.size - 1
            var idx = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    time < startsMs[mid] -> hi = mid - 1
                    time >= endsMs[mid] -> lo = mid + 1
                    else -> { idx = mid; break }
                }
            }
            if (idx >= 0) {
                sums[idx] += value
                counts[idx]++
            }
        }
        return axis.bucketStarts.mapIndexed { i, start ->
            TrendBucket(start, if (counts[i] == 0) null else sums[i] / counts[i])
        }
    }

    private fun granularityFor(range: TrendRange): EnvironmentalHistoryService.Granularity =
        when (range) {
            TrendRange.Day -> EnvironmentalHistoryService.Granularity.Hourly
            else -> EnvironmentalHistoryService.Granularity.Daily
        }

    private fun unitsFor(metric: HealthConnectMetric): String = when (metric) {
        HealthConnectMetric.Steps -> "steps"
        HealthConnectMetric.ActiveCaloriesBurned -> "kcal"
        HealthConnectMetric.ExerciseSession -> "sessions"
        HealthConnectMetric.HeartRate -> "bpm"
        HealthConnectMetric.RestingHeartRate -> "bpm"
        HealthConnectMetric.OxygenSaturation -> "%"
        HealthConnectMetric.RespiratoryRate -> "rpm"
        HealthConnectMetric.SleepSession -> "min"
        HealthConnectMetric.Weight -> "kg"
        HealthConnectMetric.Height -> "m"
        HealthConnectMetric.BodyFat -> "%"
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private data class RenderInputs(
        val logs: List<SymptomLog>,
        val symptomOptions: List<SymptomOption>,
        val overlayOptions: List<OverlayOption>,
        val effectiveSymptom: String?,
        val range: TrendRange,
        val overlayIds: Set<String>,
        val anchor: Instant,
    )

    companion object {
        /**
         * The overlay the Home preview card seeds with when the user
         * has never toggled anything on the fullscreen dashboard.
         * Sleep is the "most common" signal users care about
         * correlating with symptoms; the id is the HC-metric form
         * produced by `"hc_${storageKey}"`. If the user hasn't opted
         * into Sleep in Health Data Settings the overlay simply won't
         * resolve to an option and the card renders a bare symptom line.
         */
        const val DEFAULT_HOME_OVERLAY_ID: String = "hc_sleep_session"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return TrendVisualisationViewModel(
                    symptomRepository = app.container.symptomLogRepository,
                    healthHistoryRepository = app.container.healthHistoryRepository,
                    healthPreferences = app.container.healthPreferencesRepository,
                    environmentalHistoryRepository = app.container.environmentalHistoryRepository,
                    uiPreferencesRepository = app.container.uiPreferencesRepository,
                ) as T
            }
        }
    }
}

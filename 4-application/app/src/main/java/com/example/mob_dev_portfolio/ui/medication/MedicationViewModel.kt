package com.example.mob_dev_portfolio.ui.medication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.medication.DoseEvent
import com.example.mob_dev_portfolio.data.medication.DoseStatus
import com.example.mob_dev_portfolio.data.medication.HISTORY_RETENTION_MILLIS
import com.example.mob_dev_portfolio.data.medication.MedicationReminder
import com.example.mob_dev_portfolio.data.medication.MedicationRepository
import com.example.mob_dev_portfolio.data.medication.NextFireCalculator
import com.example.mob_dev_portfolio.data.medication.ReminderFrequency
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.notifications.MedicationReminderNotifier
import com.example.mob_dev_portfolio.reminders.MedicationReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * List + history screen state for [MedicationListScreen].
 *
 * The list sorts active reminders by their next-fire instant so the
 * user sees "what's coming up" first (FR-MR-02). The history feed is a
 * separate Flow so the two sections update independently — flipping a
 * reminder's enabled toggle re-sorts the list without churning the
 * history rows.
 */
data class MedicationListUiState(
    val reminders: List<ReminderRow> = emptyList(),
    val history: List<HistoryRow> = emptyList(),
    val globalEnabled: Boolean = true,
)

data class ReminderRow(
    val reminder: MedicationReminder,
    val nextFire: Instant?,
    val frequencyLabel: String,
)

data class HistoryRow(
    val event: DoseEvent,
    val medicationName: String,
)

class MedicationListViewModel(
    private val repository: MedicationRepository,
    private val uiPrefs: UiPreferencesRepository,
    private val scheduler: MedicationReminderScheduler,
    private val notifier: MedicationReminderNotifier,
    private val now: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<MedicationListUiState> = combine(
        repository.observeAll(),
        // History window is the 30-day retention period — pruning on
        // cold start (AuraApplication) keeps the query cheap.
        kotlinx.coroutines.flow.flow { emit(now().toEpochMilli()) }
            .flatMapLatest { nowMs ->
                repository.observeEventsSince(HISTORY_RETENTION_MILLIS, nowMs)
            },
        uiPrefs.medicationRemindersEnabled,
    ) { reminders, events, enabled ->
        val rows = reminders.map { reminder ->
            ReminderRow(
                reminder = reminder,
                nextFire = NextFireCalculator.nextFire(reminder, now(), zone),
                frequencyLabel = frequencyLabel(reminder),
            )
        }.sortedWith(
            // Nulls last — a disabled reminder or finished one-off still
            // shows in the list so the user can re-enable / delete, but
            // sinks to the bottom.
            compareBy(nullsLast()) { it.nextFire?.toEpochMilli() },
        )

        val namesById = reminders.associateBy(MedicationReminder::id)
        val history = events.map { evt ->
            HistoryRow(
                event = evt,
                medicationName = namesById[evt.medicationId]?.name ?: "(deleted)",
            )
        }
        MedicationListUiState(rows, history, enabled)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        MedicationListUiState(),
    )

    fun setEnabled(reminder: MedicationReminder, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(reminder.id, enabled)
            val updated = repository.getById(reminder.id) ?: return@launch
            if (enabled) scheduler.schedule(updated)
            else scheduler.cancel(reminder.id)
        }
    }

    fun delete(reminder: MedicationReminder) {
        viewModelScope.launch {
            scheduler.cancel(reminder.id)
            repository.delete(reminder.id)
        }
    }

    /**
     * In-app equivalent of the "Taken" action on the notification.
     * Updates the pending event to Taken, then dismisses any live
     * notification so the tray matches the new DB state.
     */
    fun markTaken(event: DoseEvent) {
        if (event.status != DoseStatus.Pending) return
        viewModelScope.launch {
            repository.markTaken(event.id, now().toEpochMilli())
            notifier.cancelNotification(event.medicationId)
        }
    }

    /**
     * In-app equivalent of the "Snooze 15 min" action. Marks the
     * current event SNOOZED and arms a fresh alarm 15 minutes out
     * regardless of the reminder's recurrence — identical path to
     * [com.example.mob_dev_portfolio.reminders.MedicationActionReceiver]'s handling. Returning the
     * snooze-until instant lets the UI surface "Snoozed until HH:MM"
     * without a second read.
     */
    fun snooze(event: DoseEvent) {
        if (event.status != DoseStatus.Pending) return
        viewModelScope.launch {
            val nowMs = now().toEpochMilli()
            repository.markSnoozed(event.id, nowMs)
            scheduler.scheduleAt(
                event.medicationId,
                nowMs + MedicationReminderScheduler.SNOOZE_MILLIS,
            )
            notifier.cancelNotification(event.medicationId)
        }
    }

    private fun frequencyLabel(reminder: MedicationReminder): String = when (val f = reminder.frequency) {
        ReminderFrequency.Daily -> "Every day"
        is ReminderFrequency.WeeklyDays -> NextFireCalculator.maskToLabel(f.daysMask)
        is ReminderFrequency.OneOff -> "One-off"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return MedicationListViewModel(
                    repository = app.container.medicationRepository,
                    uiPrefs = app.container.uiPreferencesRepository,
                    scheduler = app.container.medicationReminderScheduler,
                    notifier = app.container.medicationReminderNotifier,
                ) as T
            }
        }
    }
}

/**
 * Editor-form state. Kept a plain data class + a few mutator lambdas
 * rather than a library (MVI etc) because the form has 5 fields and
 * drag-in a state container library would be more ceremony than value.
 */
data class MedicationEditorUiState(
    val id: Long = 0L,
    val name: String = "",
    val dosage: String = "",
    /** Minutes-from-midnight. */
    val timeMinutes: Int = 8 * 60,
    val frequencyKind: FrequencyKind = FrequencyKind.Daily,
    /** Bits 0..6 = Mon..Sun (ISO week). Only consulted for Weekly. */
    val weeklyMask: Int = ReminderFrequency.MASK_ALL_DAYS,
    /** Epoch-ms for the one-off variant; null until the user picks. */
    val oneOffAtEpochMillis: Long? = null,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && when (frequencyKind) {
            FrequencyKind.Daily -> true
            FrequencyKind.Weekly -> weeklyMask != 0
            FrequencyKind.OneOff -> oneOffAtEpochMillis != null &&
                (oneOffAtEpochMillis > System.currentTimeMillis())
        }
}

enum class FrequencyKind { Daily, Weekly, OneOff }

class MedicationEditorViewModel(
    private val reminderId: Long?,
    private val repository: MedicationRepository,
    private val scheduler: MedicationReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(MedicationEditorUiState(loading = reminderId != null))
    val state: StateFlow<MedicationEditorUiState> = _state.asStateFlow()

    init {
        if (reminderId != null) {
            viewModelScope.launch {
                val existing = repository.getById(reminderId)
                if (existing == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Reminder not found",
                    )
                    return@launch
                }
                val (kind, mask, oneOff) = when (val f = existing.frequency) {
                    ReminderFrequency.Daily -> Triple(FrequencyKind.Daily, ReminderFrequency.MASK_ALL_DAYS, null)
                    is ReminderFrequency.WeeklyDays -> Triple(FrequencyKind.Weekly, f.daysMask, null)
                    is ReminderFrequency.OneOff -> Triple(FrequencyKind.OneOff, 0, f.atEpochMillis)
                }
                _state.value = MedicationEditorUiState(
                    id = existing.id,
                    name = existing.name,
                    dosage = existing.dosage,
                    timeMinutes = existing.timeOfDayMinutes,
                    frequencyKind = kind,
                    weeklyMask = mask.coerceAtLeast(0),
                    oneOffAtEpochMillis = oneOff,
                    enabled = existing.enabled,
                    loading = false,
                )
            }
        }
    }

    fun updateName(value: String) { _state.value = _state.value.copy(name = value, error = null) }
    fun updateDosage(value: String) { _state.value = _state.value.copy(dosage = value) }
    fun updateTime(minutes: Int) { _state.value = _state.value.copy(timeMinutes = minutes.coerceIn(0, 23 * 60 + 59)) }
    fun updateFrequency(kind: FrequencyKind) { _state.value = _state.value.copy(frequencyKind = kind) }
    fun toggleWeekday(bit: Int) {
        val cur = _state.value
        val newMask = cur.weeklyMask xor bit
        _state.value = cur.copy(weeklyMask = newMask)
    }
    fun updateOneOff(epochMillis: Long?) { _state.value = _state.value.copy(oneOffAtEpochMillis = epochMillis) }
    fun updateEnabled(enabled: Boolean) { _state.value = _state.value.copy(enabled = enabled) }

    fun save() {
        val cur = _state.value
        if (!cur.canSave || cur.saving) return
        _state.value = cur.copy(saving = true, error = null)
        viewModelScope.launch {
            val frequency = when (cur.frequencyKind) {
                FrequencyKind.Daily -> ReminderFrequency.Daily
                FrequencyKind.Weekly -> ReminderFrequency.WeeklyDays(cur.weeklyMask)
                FrequencyKind.OneOff -> ReminderFrequency.OneOff(
                    cur.oneOffAtEpochMillis
                        ?: error("canSave should have guarded non-null oneOffAtEpochMillis"),
                )
            }
            // Preserve the original createdAt on edit so sort orders and
            // analytics queries anchored on creation time remain stable.
            val existingCreatedAt = if (cur.id != 0L) {
                repository.getById(cur.id)?.createdAtEpochMillis
            } else null
            val reminder = MedicationReminder(
                id = cur.id,
                name = cur.name.trim(),
                dosage = cur.dosage.trim(),
                frequency = frequency,
                timeOfDayMinutes = cur.timeMinutes,
                enabled = cur.enabled,
                createdAtEpochMillis = existingCreatedAt ?: System.currentTimeMillis(),
            )
            val newId = runCatching { repository.upsert(reminder) }
                .getOrElse { t ->
                    _state.value = cur.copy(saving = false, error = "Could not save: ${t.message}")
                    return@launch
                }
            val saved = repository.getById(newId) ?: reminder.copy(id = newId)
            scheduler.schedule(saved)
            _state.value = _state.value.copy(saving = false, saved = true)
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _state.value.id
        if (id == 0L) { onDone(); return }
        viewModelScope.launch {
            scheduler.cancel(id)
            repository.delete(id)
            onDone()
        }
    }

    companion object {
        fun factory(reminderId: Long?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return MedicationEditorViewModel(
                    reminderId = reminderId,
                    repository = app.container.medicationRepository,
                    scheduler = app.container.medicationReminderScheduler,
                ) as T
            }
        }
    }
}

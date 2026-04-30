package com.example.mob_dev_portfolio.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.ai.AnalysisSanitizer
import com.example.mob_dev_portfolio.data.preferences.BiologicalSex
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * State for the demographic profile screen — the dedicated home for
 * identity fields the AI analysis uses.
 *
 * The screen is a thin editor: it loads the profile once, lets the user
 * change [dateOfBirthEpochMillis] and [biologicalSex], and persists
 * synchronously so the next analysis run picks up the change.
 *
 * [ageRangePreview] mirrors the logic AnalysisSanitizer uses — shown
 * in the UI so users can see exactly how their DOB maps onto the
 * coarse bucket that reaches the model. Reinforces the privacy story.
 */
data class DemographicProfileUiState(
    val dateOfBirthEpochMillis: Long? = null,
    val biologicalSex: BiologicalSex? = null,
    val ageRangePreview: String = AnalysisSanitizer.UNKNOWN_AGE_RANGE,
)

/**
 * ViewModel for the demographic profile editor.
 *
 * The screen exists to give the user a single, transparent place to
 * inspect / change the data that gets bucketised into the coarse
 * [AnalysisSanitizer] inputs. Persistence is fire-and-forget on each
 * field change rather than gated behind a Save button — the values are
 * single-field and the user expects them to stick the moment they
 * change, the same way they would on the system Settings app.
 */
class DemographicProfileViewModel(
    private val repository: UserProfileRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow(DemographicProfileUiState())
    val state: StateFlow<DemographicProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val initial = runCatching { repository.profile.first() }.getOrNull() ?: UserProfile()
            _state.value = DemographicProfileUiState(
                dateOfBirthEpochMillis = initial.dateOfBirthEpochMillis,
                biologicalSex = initial.biologicalSex,
                ageRangePreview = AnalysisSanitizer.ageRange(
                    initial.dateOfBirthEpochMillis,
                    nowProvider(),
                ),
            )
        }
    }

    fun onDobChange(millis: Long?) {
        _state.value = _state.value.copy(
            dateOfBirthEpochMillis = millis,
            ageRangePreview = AnalysisSanitizer.ageRange(millis, nowProvider()),
        )
        viewModelScope.launch { repository.setDateOfBirth(millis) }
    }

    fun onBiologicalSexChange(sex: BiologicalSex?) {
        _state.value = _state.value.copy(biologicalSex = sex)
        viewModelScope.launch { repository.setBiologicalSex(sex) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return DemographicProfileViewModel(
                    repository = app.container.userProfileRepository,
                ) as T
            }
        }
    }
}

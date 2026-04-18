package com.example.mob_dev_portfolio.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

class HomeViewModel(
    repository: SymptomLogRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val logs: StateFlow<List<SymptomLog>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val insights: StateFlow<HomeInsights> = repository.observeAll()
        .map { HomeInsights.compute(it, today = todayProvider(), zone = zone) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            HomeInsights.compute(emptyList(), today = todayProvider(), zone = zone),
        )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return HomeViewModel(app.container.symptomLogRepository) as T
            }
        }
    }
}

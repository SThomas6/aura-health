package com.example.mob_dev_portfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.ui.log.LogSymptomScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogSymptomScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildViewModel(): LogSymptomViewModel {
        val fakeRepo = FakeRepository()
        return LogSymptomViewModel(fakeRepo, nowProvider = { 1_800_000_000_000L })
    }

    @Test
    fun saving_empty_form_shows_aggregated_error_banner() {
        val viewModel = buildViewModel()
        composeRule.setContent {
            AuraTheme {
                LogSymptomScreen(onBack = {}, onSaved = {}, viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag("btn_save").performClick()

        composeRule.onNodeWithTag("error_banner").assertIsDisplayed()
        composeRule.onAllNodesWithText("• Symptom type is required").assertCountEquals(1)
        composeRule.onAllNodesWithText("• Description is required").assertCountEquals(1)
    }

    @Test
    fun severity_slider_displays_selected_value() {
        val viewModel = buildViewModel()
        composeRule.setContent {
            AuraTheme {
                LogSymptomScreen(onBack = {}, onSaved = {}, viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag("severity_value").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()

        composeRule.onNodeWithTag("severity_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }

        composeRule.onNodeWithText("9").assertIsDisplayed()
    }

    @Test
    fun filling_required_fields_clears_errors() {
        val viewModel = buildViewModel()
        composeRule.setContent {
            AuraTheme {
                LogSymptomScreen(onBack = {}, onSaved = {}, viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag("btn_save").performClick()
        composeRule.onNodeWithTag("error_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("field_symptom").performTextInput("Headache")
        composeRule.onNodeWithTag("field_description").performTextInput("Dull ache on the left side")
    }
}

private class FakeRepository : SymptomLogRepository(FakeDao()) {
    override suspend fun save(log: SymptomLog): Long = 1L
}

private class FakeDao : com.example.mob_dev_portfolio.data.SymptomLogDao {
    private val flow = MutableStateFlow<List<com.example.mob_dev_portfolio.data.SymptomLogEntity>>(emptyList())
    override suspend fun insert(entity: com.example.mob_dev_portfolio.data.SymptomLogEntity): Long = 1L
    override fun observeAll() = flow.asStateFlow()
    override fun observeCount() = MutableStateFlow(0).asStateFlow()
    override suspend fun delete(id: Long) = Unit
}

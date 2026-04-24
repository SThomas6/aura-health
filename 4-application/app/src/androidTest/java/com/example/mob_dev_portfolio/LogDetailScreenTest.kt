package com.example.mob_dev_portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.ui.detail.LogDetailScreen
import com.example.mob_dev_portfolio.ui.detail.LogDetailViewModel
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleLog = SymptomLog(
        id = 42L,
        symptomName = "Migraine",
        description = "Throbbing behind left eye",
        startEpochMillis = 1_800_000_000_000L,
        endEpochMillis = null,
        severity = 8,
        medication = "Ibuprofen 400mg",
        contextTags = listOf("stress", "poor sleep"),
        notes = "Came on after a long meeting",
        createdAtEpochMillis = 1_800_000_000_000L,
    )

    @Test
    fun detail_screen_renders_all_fields() {
        val repo = FakeDetailRepository(sampleLog)
        val viewModel = LogDetailViewModel(sampleLog.id, repo)

        composeRule.setContent {
            AuraTheme {
                LogDetailScreen(
                    id = sampleLog.id,
                    onBack = {},
                    onEdit = {},
                    onDeleted = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag("detail_symptom").assertIsDisplayed()
        composeRule.onNodeWithText("Migraine").assertIsDisplayed()
        composeRule.onNodeWithText("Throbbing behind left eye").assertIsDisplayed()
        composeRule.onNodeWithText("Ibuprofen 400mg").assertIsDisplayed()
        composeRule.onNodeWithText("Severity 8/10").assertIsDisplayed()
    }

    @Test
    fun delete_requires_explicit_confirmation() {
        val repo = FakeDetailRepository(sampleLog)
        val viewModel = LogDetailViewModel(sampleLog.id, repo)
        var onDeletedFired = false

        composeRule.setContent {
            AuraTheme {
                LogDetailScreen(
                    id = sampleLog.id,
                    onBack = {},
                    onEdit = {},
                    onDeleted = { onDeletedFired = true },
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag("btn_delete").performClick()
        composeRule.onNodeWithTag("dialog_delete_confirm").assertIsDisplayed()
        assertFalse("Delete must not execute before confirmation", repo.deleted)
        assertFalse("onDeleted must not fire before confirmation", onDeletedFired)

        composeRule.onNodeWithTag("btn_cancel_delete").performClick()
        composeRule.onNodeWithTag("dialog_delete_confirm").assertDoesNotExist()
        assertFalse("Cancel must not execute the delete", repo.deleted)
    }

    @Test
    fun confirming_delete_invokes_repository_and_onDeleted() {
        val repo = FakeDetailRepository(sampleLog)
        val viewModel = LogDetailViewModel(sampleLog.id, repo)
        var onDeletedFired = false

        composeRule.setContent {
            AuraTheme {
                LogDetailScreen(
                    id = sampleLog.id,
                    onBack = {},
                    onEdit = {},
                    onDeleted = { onDeletedFired = true },
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag("btn_delete").performClick()
        composeRule.onNodeWithTag("btn_confirm_delete").performClick()

        composeRule.waitUntil(timeoutMillis = 2_000L) { repo.deleted }
        composeRule.waitUntil(timeoutMillis = 2_000L) { onDeletedFired }
    }

    @Test
    fun edit_button_invokes_onEdit_with_id() {
        val repo = FakeDetailRepository(sampleLog)
        val viewModel = LogDetailViewModel(sampleLog.id, repo)
        var editedId: Long? = null

        composeRule.setContent {
            AuraTheme {
                LogDetailScreen(
                    id = sampleLog.id,
                    onBack = {},
                    onEdit = { editedId = it },
                    onDeleted = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag("btn_edit").performClick()
        assertEquals(sampleLog.id, editedId)
    }

    @Test
    fun missing_log_renders_not_found_state_instead_of_spinner() {
        val repo = FakeDetailRepository(initial = null)
        val viewModel = LogDetailViewModel(sampleLog.id, repo)
        var backFired = false

        composeRule.setContent {
            AuraTheme {
                LogDetailScreen(
                    id = sampleLog.id,
                    onBack = { backFired = true },
                    onEdit = {},
                    onDeleted = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag("detail_not_found").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_not_found_back").performClick()
        assertTrue("Back callback should fire from the not-found state", backFired)
    }
}

private class FakeDetailRepository(
    initial: SymptomLog?,
) : SymptomLogRepository(NoOpDao()) {

    private val state = MutableStateFlow<SymptomLog?>(initial)
    var deleted: Boolean = false
        private set

    override fun observeById(id: Long): Flow<SymptomLog?> = state.asStateFlow().map { it }

    override suspend fun delete(id: Long) {
        deleted = true
        state.value = null
    }
}

private class NoOpDao : com.example.mob_dev_portfolio.data.SymptomLogDao {
    override suspend fun insert(entity: SymptomLogEntity): Long = 0L
    override suspend fun update(entity: SymptomLogEntity): Int = 0
    override fun observeAll() = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ) = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeById(id: Long) = MutableStateFlow<SymptomLogEntity?>(null).asStateFlow()
    override fun observeCount() = MutableStateFlow(0).asStateFlow()
    override suspend fun delete(id: Long) = Unit
    override suspend fun listChronologicalAsc(): List<SymptomLogEntity> = emptyList()
    override suspend fun totalCount(): Int = 0
    override suspend fun averageSeverity(): Double? = null
}

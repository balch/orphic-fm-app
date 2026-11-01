package org.balch.orpheus.djapp.vibepresets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VibePresetBarTest {

    private val three = listOf(
        PresetRow(1, "Quiet Night", 1_000L),
        PresetRow(2, "Deep Bass", 2_000L),
        PresetRow(3, "Loud Drums", 3_000L),
    )

    // Built through the real formatter, so the sheet and chipText cannot drift apart.
    private val draftChanges = listOf(
        PresetChange(PresetField.MIX_KEYS, 0.5f, 0.75f),
        PresetChange(PresetField.PULSAR_BPM, 92f, 104f),
    )

    private val five = three + listOf(PresetRow(4, "Fast Keys", 4_000L), PresetRow(5, "Slow Horn", 5_000L))

    // Holds its own state the way the view model does, so an opened draft shows up as a sheet.
    private class Holder(initial: VibePresetsUiState, private val draft: CaptureDraft) {
        val state = MutableStateFlow(initial)
        val selected = mutableListOf<Int>()
        val saves = mutableListOf<Pair<String, Int?>>()
        val deleted = mutableListOf<Int>()
        var originalLoads = 0
        val feature = object : VibePresetsFeature {
            override val stateFlow: StateFlow<VibePresetsUiState> = state
            override val actions = VibePresetsActions.EMPTY.copy(
                openCapture = { state.update { it.copy(draft = draft) } },
                cancelCapture = { state.update { it.copy(draft = null) } },
                saveCapture = { name, replaceId -> saves += name to replaceId },
                select = { selected += it },
                loadOriginal = { originalLoads++ },
                delete = { deleted += it },
            )
        }
    }

    private fun holder(
        presets: List<PresetRow> = three,
        selectedId: Int? = 2,
        changed: Boolean = true,
        draft: CaptureDraft = CaptureDraft(
            suggestedName = "Bright Keys",
            chips = draftChanges.map { it.chipText() },
            count = presets.size + 1,
            full = false,
            replaceDefaultId = null,
        ),
    ) = Holder(
        VibePresetsUiState(
            available = true,
            barShown = true,
            vibeName = "A Whale's Dream",
            presets = presets,
            selectedId = selectedId,
            changed = changed,
        ),
        draft,
    )

    // The bar and its sheets, wired the way DjAppScreen wires them.
    private fun ComposeUiTest.show(holder: Holder) {
        setContent {
            OrpheusTheme {
                var manage by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth()) {
                        VibePresetBar(feature = holder.feature, onManage = { manage = true })
                    }
                    VibePresetSheets(
                        feature = holder.feature,
                        isLandscape = false,
                        manageOpen = manage,
                        onManageDismiss = { manage = false },
                    )
                }
            }
        }
    }

    @Test
    fun showsOneDotPerPreset() = runComposeUiTest {
        val holder = holder()
        show(holder)
        onAllNodesWithContentDescription(PRESET_DOT_PREFIX, substring = true).assertCountEquals(3)
        onNodeWithContentDescription("${PRESET_DOT_PREFIX}Deep Bass").assertIsSelected()
        onNodeWithContentDescription(ORIGINAL_LABEL).assertIsNotSelected()
        onNodeWithText(CAPTURE_LABEL).assertExists()

        // No presets and nothing changed: the original, selected, then ≡ and CAPTURE.
        holder.state.update { it.copy(presets = emptyList(), selectedId = null, changed = false) }
        waitForIdle()
        onAllNodesWithContentDescription(PRESET_DOT_PREFIX, substring = true).assertCountEquals(0)
        onNodeWithContentDescription(ORIGINAL_LABEL).assertIsSelected()
        onAllNodesWithContentDescription(NOW_DOT_DESCRIPTION).assertCountEquals(0)
        onNodeWithContentDescription(MANAGE_DESCRIPTION).assertExists()
        onNodeWithText(CAPTURE_LABEL).assertExists()
    }

    @Test
    fun nowDotOnlyWhenChanged() = runComposeUiTest {
        val holder = holder(changed = false)
        show(holder)
        onAllNodesWithContentDescription(NOW_DOT_DESCRIPTION).assertCountEquals(0)
        onAllNodesWithText("· changed").assertCountEquals(0)

        holder.state.update { it.copy(changed = true) }
        waitForIdle()
        onAllNodesWithContentDescription(NOW_DOT_DESCRIPTION).assertCountEquals(1)
        onNodeWithText("· changed").assertExists()
    }

    @Test
    fun tapDotSelects() = runComposeUiTest {
        val holder = holder(changed = false)
        show(holder)
        onNodeWithContentDescription("${PRESET_DOT_PREFIX}Loud Drums").performClick()
        assertEquals(listOf(3), holder.selected)
    }

    @Test
    fun nowDotIsNotAButton() = runComposeUiTest {
        val holder = holder(changed = true)
        show(holder)
        onNodeWithContentDescription(NOW_DOT_DESCRIPTION).performClick()
        waitForIdle()
        assertTrue(holder.selected.isEmpty(), "tapping the now-dot selected something")
        onAllNodesWithText(DISCARD_PROMPT).assertCountEquals(0)
    }

    @Test
    fun presetDotAsksBeforeDiscardingChanges() = runComposeUiTest {
        val holder = holder(changed = true)
        show(holder)
        onNodeWithContentDescription("${PRESET_DOT_PREFIX}Loud Drums").performClick()
        waitForIdle()
        assertTrue(holder.selected.isEmpty(), "the first tap discarded the changes")
        onNodeWithText(DISCARD_PROMPT).assertExists()

        onNodeWithText(DISCARD_LABEL).performClick()
        assertEquals(listOf(3), holder.selected)
        assertEquals(0, holder.originalLoads)
    }

    @Test
    fun manageButtonOpensManage() = runComposeUiTest {
        val holder = holder()
        show(holder)
        onAllNodesWithText(ORIGINAL_LABEL).assertCountEquals(0)
        onNodeWithContentDescription(MANAGE_DESCRIPTION).performClick()
        waitForIdle()
        onNodeWithText("PRESETS · A WHALE'S DREAM").assertExists()
        onNodeWithText(ORIGINAL_LABEL).assertExists()
        assertTrue(holder.selected.isEmpty(), "opening the sheet also selected a preset")
    }

    @Test
    fun manageButtonWithNoPresetsShowsOriginal() = runComposeUiTest {
        val holder = holder(presets = emptyList(), selectedId = null, changed = false)
        show(holder)
        onNodeWithContentDescription(MANAGE_DESCRIPTION).performClick()
        waitForIdle()
        onNodeWithText("0 of 5").assertExists()
        // The bar's label and the sheet's row.
        onAllNodesWithText(ORIGINAL_LABEL).assertCountEquals(2)
    }

    @Test
    fun originalDotLoadsStraightAwayWhenNothingChanged() = runComposeUiTest {
        val holder = holder(changed = false)
        show(holder)
        onNodeWithContentDescription(ORIGINAL_LABEL).performClick()
        waitForIdle()
        assertEquals(1, holder.originalLoads)
        onAllNodesWithText(DISCARD_PROMPT).assertCountEquals(0)
    }

    @Test
    fun originalDotAsksBeforeDiscardingChanges() = runComposeUiTest {
        val holder = holder(changed = true)
        show(holder)
        onNodeWithContentDescription(ORIGINAL_LABEL).performClick()
        waitForIdle()
        assertEquals(0, holder.originalLoads, "the first tap discarded the changes")
        onNodeWithText(DISCARD_PROMPT).assertExists()

        onNodeWithText(CANCEL_LABEL).performClick()
        waitForIdle()
        onAllNodesWithText(DISCARD_PROMPT).assertCountEquals(0)
        assertEquals(0, holder.originalLoads, "cancel discarded the changes")

        onNodeWithContentDescription(ORIGINAL_LABEL).performClick()
        waitForIdle()
        onNodeWithText(DISCARD_LABEL).performClick()
        assertEquals(1, holder.originalLoads)
    }

    @Test
    fun deleteAsksAgainBeforeDeleting() = runComposeUiTest {
        val holder = holder()
        show(holder)
        onNodeWithContentDescription(MANAGE_DESCRIPTION).performClick()
        waitForIdle()
        // The sheet opens on the selected preset, Deep Bass.
        onNodeWithText("DELETE").performClick()
        waitForIdle()
        assertTrue(holder.deleted.isEmpty(), "the first DELETE tap deleted")
        onNodeWithText("DELETE?").performClick()
        assertEquals(listOf(2), holder.deleted)
    }

    @Test
    fun captureOpensDraft() = runComposeUiTest {
        val holder = holder()
        show(holder)
        onAllNodesWithText("CAPTURE PRESET").assertCountEquals(0)
        onNodeWithText(CAPTURE_LABEL).performClick()
        waitForIdle()
        onNodeWithText("CAPTURE PRESET").assertExists()
        onNodeWithText("A Whale's Dream · 4 of 5").assertExists()
        onNode(hasSetTextAction() and hasText("Bright Keys")).assertExists()
        onNodeWithText("MIX: KEYS 50%→75%").assertExists()
        onNodeWithText("PULSAR: BPM 92→104").assertExists()
        onAllNodesWithText("Replace").assertCountEquals(0)

        onNodeWithText("SAVE").performClick()
        assertEquals(listOf<Pair<String, Int?>>("Bright Keys" to null), holder.saves)
    }

    @Test
    fun fullDraftShowsReplaceList() = runComposeUiTest {
        val holder = holder(
            presets = five,
            draft = CaptureDraft("Bright Keys", emptyList(), count = 5, full = true, replaceDefaultId = 1),
        )
        show(holder)
        onNodeWithText(CAPTURE_LABEL).performClick()
        waitForIdle()
        onNodeWithText("A Whale's Dream · 5 of 5").assertExists()
        onNodeWithText("Replace").assertExists()
        onNodeWithContentDescription("${REPLACE_PREFIX}Quiet Night").assertIsSelected()
        onAllNodesWithContentDescription(REPLACE_PREFIX, substring = true).assertCountEquals(5)

        onNodeWithContentDescription("${REPLACE_PREFIX}Fast Keys").performClick()
        onNodeWithContentDescription("${REPLACE_PREFIX}Fast Keys").assertIsSelected()
        onNodeWithText("SAVE").performClick()
        assertEquals(listOf<Pair<String, Int?>>("Bright Keys" to 4), holder.saves)
    }

    @Test
    fun cancelLeavesPresetsUntouched() = runComposeUiTest {
        val holder = holder()
        show(holder)
        onNodeWithText(CAPTURE_LABEL).performClick()
        waitForIdle()
        onNodeWithText("CANCEL").performClick()
        waitForIdle()
        onAllNodesWithText("CAPTURE PRESET").assertCountEquals(0)
        assertTrue(holder.saves.isEmpty(), "cancel saved a preset")
        assertEquals(three, holder.state.value.presets)
        assertEquals(2, holder.state.value.selectedId)
    }
}

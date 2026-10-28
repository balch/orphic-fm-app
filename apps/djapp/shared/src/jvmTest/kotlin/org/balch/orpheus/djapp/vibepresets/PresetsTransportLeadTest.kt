package org.balch.orpheus.djapp.vibepresets

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarSelectors
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The PRESETS toggle takes PERC's place on Pulsar's transport row in the OG edition only. */
@OptIn(ExperimentalTestApi::class)
class PresetsTransportLeadTest {

    private class Holder(available: Boolean) {
        val state = MutableStateFlow(VibePresetsUiState(available = available))
        val feature = object : VibePresetsFeature {
            override val stateFlow: StateFlow<VibePresetsUiState> = state
            override val actions = VibePresetsActions.EMPTY.copy(
                setBarShown = { shown -> state.update { it.copy(barShown = shown) } },
            )
        }
    }

    // Wired the way DjAppScreen wires it.
    private fun ComposeUiTest.show(holder: Holder) {
        setContent {
            OrpheusTheme {
                PulsarPanel(
                    pulsar = PulsarViewModel.previewFeature(),
                    isExpanded = true,
                    showCollapsedHeader = false,
                    fillHeight = false,
                    selectors = PulsarSelectors.None,
                    transportLead = rememberPresetsTransportLead(holder.feature),
                )
            }
        }
    }

    @Test
    fun ogEditionShowsPresetsInPercsPlace() = runComposeUiTest {
        val holder = Holder(available = true)
        show(holder)
        onAllNodesWithText("PERC").assertCountEquals(0)
        onNodeWithText("BPM").assertExists()
        onNodeWithText(PRESETS_TOGGLE_LABEL).performClick()
        waitForIdle()
        assertEquals(true, holder.state.value.barShown)
        onNodeWithText(PRESETS_TOGGLE_LABEL).assertIsSelected()
    }

    @Test
    fun unavailableKeepsPerc() = runComposeUiTest {
        val holder = Holder(available = false)
        show(holder)
        onNodeWithText("PERC").assertExists()
        onAllNodesWithText(PRESETS_TOGGLE_LABEL).assertCountEquals(0)
    }

    @Test
    fun theToggleKeepsTheRowHeight() = runComposeUiTest {
        val holder = Holder(available = false)
        show(holder)
        val withPerc = onNodeWithText("ENERGY").getBoundsInRoot()
        holder.state.update { it.copy(available = true) }
        waitForIdle()
        onNodeWithText(PRESETS_TOGGLE_LABEL).assertExists()
        assertEquals(withPerc, onNodeWithText("ENERGY").getBoundsInRoot(), "the macro row moved")
    }
}

package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.djapp.ai.licklab.LICK_LAB_TAG
import org.balch.orpheus.djapp.ai.licklab.LickLabActions
import org.balch.orpheus.djapp.ai.licklab.LickLabFeature
import org.balch.orpheus.djapp.ai.licklab.LickLabUiState
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LickLabMixerTest {

    private val mixer = object : AiMixerFeature {
        override val stateFlow: StateFlow<AiMixerUiState> = MutableStateFlow(
            AiMixerUiState(
                vibeName = "Test Vibe",
                tracks = BenchMeters.CHANNEL_NAMES.take(8).mapIndexed { t, name ->
                    MixerTrack(t, name, "SPK/MST", levelDb = 0f, pan = 0f, filePan = 0f, reverb = 0f, delay = 0f,
                        muted = false, soloed = false, silenced = false)
                },
            ),
        )
        override val actions = AiMixerActions.EMPTY
        override val meters: Flow<MeterSnapshot> = MutableStateFlow(EMPTY_METERS)
    }

    private class Lab(shown: Boolean) : LickLabFeature, LickLabActions {
        val state = MutableStateFlow(LickLabUiState(shown = shown))
        var toggles = 0
        var disarms = 0
        override val stateFlow: StateFlow<LickLabUiState> = state
        override val actions: LickLabActions = this
        override fun toggleShown() {
            toggles++
            state.update { it.copy(shown = !it.shown) }
        }

        override fun disarm() {
            disarms++
        }
    }

    @Test
    fun `the toggle swaps the strips for Lick Lab and back`() = runComposeUiTest {
        val lab = Lab(shown = false)
        setContent { OrpheusTheme { AiMixerPanel(mixer, Modifier.requiredSize(1000.dp, 720.dp), lickLab = lab) } }
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(0)
        onAllNodesWithTag(stripTag(0)).assertCountEquals(1)

        onNodeWithTag(LICK_LAB_TOGGLE_TAG).performClick()
        waitForIdle()
        assertEquals(1, lab.toggles)
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(1)
        onAllNodesWithTag(stripTag(0)).assertCountEquals(0)

        onNodeWithTag(LICK_LAB_TOGGLE_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(0)
        onAllNodesWithTag(stripTag(0)).assertCountEquals(1)
    }

    @Test
    fun `the Lick Lab toggle sits under the Loop section chip`() = runComposeUiTest {
        setContent { OrpheusTheme { AiMixerPanel(mixer, Modifier.requiredSize(1000.dp, 720.dp), lickLab = Lab(shown = false)) } }
        val loop = onNodeWithText("Loop section").getBoundsInRoot()
        val toggle = onNodeWithTag(LICK_LAB_TOGGLE_TAG).getBoundsInRoot()
        assertTrue(toggle.top >= loop.bottom, "toggle top ${toggle.top} under loop bottom ${loop.bottom}")
        assertTrue(toggle.top - loop.bottom < 24.dp, "toggle right under the loop chip")
        assertTrue(abs((toggle.left - loop.left).value) < 12f, "toggle ${toggle.left} lines up with loop ${loop.left}")
    }

    @Test
    fun `a narrow mixer says Lick Lab needs a wider window and can close it`() = runComposeUiTest {
        val lab = Lab(shown = true)
        setContent { OrpheusTheme { AiMixerPanel(mixer, Modifier.requiredSize(420.dp, 720.dp), lickLab = lab) } }
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(0)
        onAllNodesWithTag(LICK_LAB_TOGGLE_TAG).assertCountEquals(0)
        onNodeWithText(LICK_LAB_NEEDS_WIDE).assertExists()
        onNodeWithText("Dismiss").performClick()
        waitForIdle()
        assertFalse(lab.state.value.shown)
    }

    @Test
    fun `going compact while Lick Lab is shown disarms and keeps it shown`() = runComposeUiTest {
        val lab = Lab(shown = true)
        var width by mutableStateOf(1000.dp)
        setContent { OrpheusTheme { AiMixerPanel(mixer, Modifier.requiredSize(width, 720.dp), lickLab = lab) } }
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(1)
        assertEquals(0, lab.disarms)

        width = 420.dp
        waitForIdle()
        assertEquals(1, lab.disarms)
        assertEquals(0, lab.toggles)
        onNodeWithText(LICK_LAB_NEEDS_WIDE).assertExists()
    }

    @Test
    fun `closing the mixer hides Lick Lab so the armed track goes back to its sequencer`() = runComposeUiTest {
        val lab = Lab(shown = true)
        var open by mutableStateOf(true)
        setContent {
            OrpheusTheme { if (open) AiMixerPanel(mixer, Modifier.requiredSize(1000.dp, 720.dp), lickLab = lab) }
        }
        onAllNodesWithTag(LICK_LAB_TAG).assertCountEquals(1)
        open = false
        waitForIdle()
        assertEquals(1, lab.toggles)
        assertFalse(lab.state.value.shown)
    }
}

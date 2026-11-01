package org.balch.orpheus.features.pulsar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The track strip's HI and LO pills pick through the engine picker; a patch pick writes its slot's harmonics too. */
@OptIn(ExperimentalTestApi::class)
class PulsarPanelPickerTest {

    private fun patch(engine: OrpheusEngineId, index: Int) =
        EngineCatalog.patches.first { it.engine == engine && it.index == index }

    private fun recording(calls: MutableList<String>) = PulsarPanelActions(
        setTrackEngineEdm = { t, e -> calls += "edm $t $e" },
        setTrackEngineSpace = { t, e -> calls += "space $t $e" },
        setTrackHarmonicsEdm = { t, v -> calls += "hedm $t $v" },
        setTrackHarmonicsSpace = { t, v -> calls += "hspace $t $v" },
    )

    @Test
    fun aPatchPickWritesItsSlotsEngineThenHarmonics() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val bellC = patch(OrpheusEngineId.DX2, 20)
        // BASS: HI plays WSH, LO plays DX2 on Bell C.
        val base = PulsarUiState(vibe = mkMinimalVibe("Picker"))
        val state = PulsarUiState(
            vibe = mkMinimalVibe("Picker"),
            trackEnginesSpace = base.trackEnginesSpace.toMutableList().also { it[3] = OrpheusEngineId.DX2.id },
            trackHarmonicsSpace = base.trackHarmonicsSpace.toMutableList().also { it[3] = bellC.harmonics },
        )
        val actions = recording(calls)
        setContent {
            OrpheusTheme {
                PulsarVoiceDetailStrip(3, state, actions, 1f, 1f, 0.dp) {}
            }
        }

        // The pill names the stored patch; a bare engine keeps its short code.
        onNodeWithText("WSH").assertExists()
        onNodeWithText("DX2 · Bell C").performClick()
        val bells = patch(OrpheusEngineId.DX2, 21)
        onNodeWithTag("engine-picker-tile-DX2:21").performClick()
        assertEquals(listOf("space 3 ${OrpheusEngineId.DX2.id}", "hspace 3 ${bells.harmonics}"), calls)
    }

    @Test
    fun aBareEngineLeavesHarmonicsAlone() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val state = PulsarUiState(vibe = mkMinimalVibe("Picker"))
        setContent {
            OrpheusTheme {
                PulsarVoiceDetailStrip(3, state, recording(calls), 1f, 1f, 0.dp) {}
            }
        }
        onNodeWithText("WSH").performClick()
        onNodeWithTag("engine-picker-tile-VCF").performClick()
        assertEquals(listOf("edm 3 ${OrpheusEngineId.VCF.id}"), calls)
    }
}

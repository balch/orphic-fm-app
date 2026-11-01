package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The duo's harmonics knob locks on DX and opens the picker on DX Patches; a patch pick sets engine then harmonics. */
@OptIn(ExperimentalTestApi::class)
class DuoEngineControlsTest {

    private fun patch(engine: OrpheusEngineId, index: Int) =
        EngineCatalog.patches.first { it.engine == engine && it.index == index }

    private class Recorder : VoiceActions by VoicePanelActions.EMPTY.toVoiceActions() {
        val calls = mutableListOf<String>()
        override fun setDuoEngine(duoIndex: Int, engineOrdinal: Int) { calls += "engine $duoIndex $engineOrdinal" }
        override fun setDuoHarmonics(duoIndex: Int, value: Float) { calls += "harmonics $duoIndex $value" }
    }

    @Test
    fun lockedHarmonicsOpensDxPatchesAndAPickSetsEngineThenHarmonics() = runComposeUiTest {
        val actions = Recorder()
        val ePiano = patch(OrpheusEngineId.DX2, 0)
        setContent {
            OrpheusTheme {
                Column {
                    DuoEngineControls(
                        duoIndex = 1,
                        engineId = OrpheusEngineId.DX2,
                        harmonics = ePiano.harmonics,
                        color = OrpheusColors.electricBlue,
                        hasNativeEngine = true,
                        glowEnergy = 0f,
                        showExternalSelection = false,
                        voiceActions = actions,
                    )
                }
            }
        }

        onNodeWithText("E piano 1").performClick()
        onNodeWithTag("engine-picker-chip-DX_PATCHES").assertIsSelected()

        onNodeWithTag("engine-picker-tile-DX2:1").performClick()
        val fender = patch(OrpheusEngineId.DX2, 1)
        assertEquals(
            listOf("engine 1 ${OrpheusEngineId.DX2.id}", "harmonics 1 ${fender.harmonics}"),
            actions.calls,
        )
    }

    @Test
    fun vaHarmonicsIsUnlockedAndAnEnginePickLeavesHarmonics() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            OrpheusTheme {
                Column {
                    DuoEngineControls(
                        duoIndex = 0,
                        engineId = OrpheusEngineId.VA,
                        harmonics = 0.3f,
                        color = OrpheusColors.neonMagenta,
                        hasNativeEngine = true,
                        glowEnergy = 0f,
                        showExternalSelection = false,
                        voiceActions = actions,
                    )
                }
            }
        }

        // Clicking an unlocked knob opens nothing.
        onNodeWithText("detune").performClick()
        onNodeWithTag("engine-picker-chip-DX_PATCHES").assertDoesNotExist()

        onNodeWithText("VA").performClick()
        onNodeWithTag("engine-picker-tile-VCF").performClick()
        assertEquals(listOf("engine 0 ${OrpheusEngineId.VCF.id}"), actions.calls)
    }
}

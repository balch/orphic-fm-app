package org.balch.orpheus.features.voice.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.features.midi.MidiUiState
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test

/** The duo header shows PATCH (no source cycler) and a dot sits on each knob a live cable reaches. */
@OptIn(ExperimentalTestApi::class)
class DuoVoiceBoxPatchTest {

    private fun androidx.compose.ui.test.ComposeUiTest.show(patch: DuoPatch, engine: OrpheusEngineId = OrpheusEngineId.VA) =
        setContent {
            OrpheusTheme {
                DuoVoiceBox(
                    modifier = Modifier,
                    voiceA = 0,
                    voiceB = 1,
                    color = OrpheusColors.neonMagenta,
                    voiceStateA = VoiceState(0),
                    voiceStateB = VoiceState(1),
                    sharpness = 0.5f,
                    envSpeedA = 0.5f,
                    envSpeedB = 0.5f,
                    duoPatch = patch,
                    duoEngine = engine.id,
                    duoHarmonics = 0.5f,
                    duoMorph = 0.5f,
                    duoModSourceLevel = 0.5f,
                    midiState = MidiUiState(),
                    voiceActions = VoicePanelActions.EMPTY.toVoiceActions(),
                    midiActions = object : MidiActions { override fun selectVoiceForLearning(voiceIndex: Int) = Unit },
                    isVoiceBeingLearned = { false },
                )
            }
        }

    private fun cable(dest: PatchDest, slot: VoiceSlot = VoiceSlot.BOTH) =
        DuoPatch().connect(PatchSource.LFO_A, dest, slot, 0.5f)

    @Test
    fun patchButtonShown() = runComposeUiTest {
        show(DuoPatch())
        onNodeWithTag("patch-button-0").assertExists()
    }

    @Test
    fun dotOnModulatedKnobOnly() = runComposeUiTest {
        show(cable(PatchDest.MORPH))
        onNodeWithTag("patch-dot-MORPH").assertExists()
        onNodeWithTag("patch-dot-HARMONICS").assertDoesNotExist()
        onNodeWithTag("patch-dot-TIMBRE").assertDoesNotExist()
    }

    @Test
    fun noSourceCyclerText() = runComposeUiTest {
        show(cable(PatchDest.MORPH))
        for (text in listOf("LFO", "WAVES", "FLUX")) onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun envCableOnSlotADotsOnlyVoiceA() = runComposeUiTest {
        show(DuoPatch(pitchBridged = true, envBridged = false).connect(PatchSource.LFO_A, PatchDest.ENV, VoiceSlot.A, 0.5f))
        onNodeWithTag("patch-dot-ENV-A").assertExists()
        onNodeWithTag("patch-dot-ENV-B").assertDoesNotExist()
    }
}

package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LickLabPanelTest {

    private class Spy : LickLabActions {
        val modes = mutableListOf<HintMode>()
        val callers = mutableListOf<Int?>()
        var boosts = 0

        override fun setHintMode(mode: HintMode) { modes += mode }
        override fun toggleBoost() { boosts++ }
        override fun setCaller(track: Int?) { callers += track }
    }

    private val state = LickLabUiState(
        shown = true,
        armedTrack = 4,
        callerTrack = 3,
        tracks = listOf(3, 4, 5, 6).map { TrackChip(it, "T$it", "SPK/MST") },
    )

    private fun feature(spy: Spy) = object : LickLabFeature {
        override val stateFlow: StateFlow<LickLabUiState> = MutableStateFlow(state)
        override val actions: LickLabActions = spy
    }

    @Test
    fun `PLAY and CALL chips of a track share a column with its wave`() = runComposeUiTest {
        setContent { OrpheusTheme { LickLabPanel(feature(Spy()), Modifier.size(1000.dp, 600.dp), animate = false) } }
        for (t in listOf(3, 4, 5, 6)) {
            val play = onNodeWithTag(playChipTag(t)).getBoundsInRoot()
            val call = onNodeWithTag(callChipTag(t)).getBoundsInRoot()
            assertEquals(play.left, call.left, "track $t left")
            assertEquals(play.right, call.right, "track $t right")
            val wave = onNodeWithTag(waveCellTag(t)).getBoundsInRoot()
            assertEquals(play.left, wave.left, "track $t wave")
        }
    }

    @Test
    fun `the CALL chip of the armed track is disabled and the others are not`() = runComposeUiTest {
        val spy = Spy()
        setContent { OrpheusTheme { LickLabPanel(feature(spy), Modifier.size(1000.dp, 600.dp), animate = false) } }
        onNodeWithTag(callChipTag(4)).assertIsEnabled()
        onNodeWithTag(callChipTag(5)).assertIsEnabled().performClick()
        assertEquals(listOf<Int?>(5), spy.callers)
    }

    @Test
    fun `BOOST and the hint switch call their actions`() = runComposeUiTest {
        val spy = Spy()
        setContent { OrpheusTheme { LickLabPanel(feature(spy), Modifier.size(1000.dp, 600.dp), animate = false) } }
        onNodeWithTag(BOOST_TAG).performClick()
        assertEquals(1, spy.boosts)
        onNodeWithTag(hintModeTag(HintMode.Root)).performClick()
        onNodeWithTag(hintModeTag(HintMode.Move)).performClick()
        assertEquals(listOf(HintMode.Root, HintMode.Move), spy.modes)
    }
}

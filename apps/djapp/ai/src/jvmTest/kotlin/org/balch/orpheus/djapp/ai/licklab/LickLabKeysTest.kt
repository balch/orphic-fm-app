package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LickLabKeysTest {

    private class Spy : LickLabActions {
        val keyDowns = mutableListOf<Key>()
        val keyUps = mutableListOf<Key>()
        val downs = mutableListOf<Int>()
        val slides = mutableListOf<Int>()
        val bends = mutableListOf<Float>()
        var ups = 0
        var releases = 0

        override fun keyDown(key: Key) { keyDowns += key }
        override fun keyUp(key: Key) { keyUps += key }
        override fun pointerDown(id: Long, midi: Int, velocity: Float) { downs += midi }
        override fun pointerSlide(id: Long, midi: Int, glideRate: Float) { slides += midi }
        override fun pointerBend(id: Long, semis: Float) { bends += semis }
        override fun pointerUp(id: Long) { ups++ }
        override fun releaseAll() { releases++ }
    }

    // C major from C4, so degree 0 is MIDI 60 and the in-key strip runs degree -7 (index 0) to 16.
    private val state = LickLabUiState(
        shown = true,
        armedTrack = 0,
        baseMidi = 60,
        keyLabel = "C major · 0 = C4",
        tracks = listOf(TrackChip(0, "LEAD", "SPK/MST"), TrackChip(1, "BASS", "VA/VA")),
    )

    private fun feature(spy: Spy) = object : LickLabFeature {
        override val stateFlow: StateFlow<LickLabUiState> = MutableStateFlow(state)
        override val actions: LickLabActions = spy
    }

    private val degree0 = stripKeyTag(7)

    @Test
    fun `a mapped key reaches Lick Lab and stops there while arrows and Enter go on`() = runComposeUiTest {
        val spy = Spy()
        val parentSaw = mutableListOf<Key>()
        setContent {
            OrpheusTheme {
                Box(Modifier.onKeyEvent { parentSaw += it.key; false }) {
                    LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false)
                }
            }
        }
        onNodeWithTag(LICK_LAB_TAG).requestFocus()
        onNodeWithTag(LICK_LAB_TAG).performKeyInput {
            pressKey(Key.A)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
        }
        assertEquals(listOf(Key.A), spy.keyDowns)
        assertEquals(listOf(Key.A), spy.keyUps)
        assertTrue(Key.A !in parentSaw, "A leaked to the parent: $parentSaw")
        for (k in listOf(Key.DirectionLeft, Key.DirectionRight, Key.Enter)) {
            assertTrue(k in parentSaw, "$k never reached the parent: $parentSaw")
        }
    }

    @Test
    fun `with the text field focused Lick Lab hears nothing`() = runComposeUiTest {
        val spy = Spy()
        setContent {
            OrpheusTheme {
                Column {
                    var text by remember { mutableStateOf("") }
                    BasicTextField(text, { text = it }, Modifier.width(200.dp).testTag("chat"))
                    LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false)
                }
            }
        }
        onNodeWithTag("chat").requestFocus()
        onNodeWithTag("chat").performKeyInput { pressKey(Key.A) }
        assertTrue(spy.keyDowns.isEmpty(), "Lick Lab heard ${spy.keyDowns}")
    }

    @Test
    fun `focus moving to a text field lets go of everything`() = runComposeUiTest {
        val spy = Spy()
        setContent {
            OrpheusTheme {
                Column {
                    var text by remember { mutableStateOf("") }
                    BasicTextField(text, { text = it }, Modifier.width(200.dp).testTag("chat"))
                    LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false)
                }
            }
        }
        onNodeWithTag(LICK_LAB_TAG).requestFocus()
        waitForIdle()
        val before = spy.releases
        onNodeWithTag("chat").requestFocus()
        waitForIdle()
        assertEquals(before + 1, spy.releases)
    }

    @Test
    fun `leaving composition lets go of everything even without a focus event`() = runComposeUiTest {
        val spy = Spy()
        var shown by mutableStateOf(true)
        setContent {
            OrpheusTheme {
                Column {
                    var text by remember { mutableStateOf("") }
                    BasicTextField(text, { text = it }, Modifier.width(200.dp).testTag("chat"))
                    if (shown) LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false)
                }
            }
        }
        // Focus elsewhere first, so removing the panel sends it no focus change.
        onNodeWithTag("chat").requestFocus()
        waitForIdle()
        val before = spy.releases
        shown = false
        waitForIdle()
        assertEquals(before + 1, spy.releases, "releaseAll was not called when the panel left")
    }

    @Test
    fun `clicking a key plays its note and dragging across slides`() = runComposeUiTest {
        val spy = Spy()
        setContent { OrpheusTheme { LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false) } }
        onNodeWithTag(degree0).performMouseInput { click() }
        assertEquals(listOf(60), spy.downs)
        assertEquals(1, spy.ups)

        onNodeWithTag(degree0).performMouseInput {
            // A mouse presses where it is, so it moves onto the key first.
            moveTo(center)
            press()
            moveBy(Offset(width.toFloat(), 0f))
            release()
        }
        assertEquals(listOf(60, 60), spy.downs)
        assertEquals(listOf(62), spy.slides)
        assertEquals(2, spy.ups)
    }

    @Test
    fun `a vertical drag bends within two semitones`() = runComposeUiTest {
        val spy = Spy()
        setContent { OrpheusTheme { LickLabPanel(feature(spy), Modifier.size(900.dp, 520.dp), animate = false) } }
        onNodeWithTag(degree0).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(0f, -height / 2f)) }
            release()
        }
        assertEquals(listOf(60), spy.downs)
        assertTrue(spy.slides.isEmpty(), "a vertical drag slid: ${spy.slides}")
        assertTrue(spy.bends.isNotEmpty(), "no bend")
        assertTrue(spy.bends.all { it in -2f..2f }, "bend out of range: ${spy.bends}")
        assertEquals(2f, spy.bends.max(), "dragging well past the key reaches the full bend up")
    }
}

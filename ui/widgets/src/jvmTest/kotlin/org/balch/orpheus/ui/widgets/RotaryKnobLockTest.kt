package org.balch.orpheus.ui.widgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A locked knob ignores drags and opens something else when clicked; held, it names its job in full. */
@OptIn(ExperimentalTestApi::class)
class RotaryKnobLockTest {

    @Test
    fun lockedKnobIgnoresDrag() = runComposeUiTest {
        var value = 0.5f
        var clicks = 0
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    label = "E piano 1",
                    locked = true,
                    onLockedClick = { clicks++ },
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        onNodeWithTag("knob").performTouchInput { swipeUp(durationMillis = 300) }

        assertEquals(0.5f, value, "a locked knob does not move")
        assertEquals(0, clicks, "a drag is not a click")
    }

    @Test
    fun lockedKnobClickOpens() = runComposeUiTest {
        var clicks = 0
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = 0.5f,
                    onValueChange = {},
                    label = "E piano 1",
                    locked = true,
                    onLockedClick = { clicks++ },
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        onNodeWithTag("knob").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun lockedKnobEnterOpensAndArrowsDoNothing() = runComposeUiTest {
        var clicks = 0
        var value = 0.5f
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    locked = true,
                    onLockedClick = { clicks++ },
                    valueFormatter = null,
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        val dial = onNode(isFocusable())
        dial.requestFocus()
        dial.performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
            pressKey(Key.DirectionUp)
        }

        assertEquals(1, clicks)
        assertEquals(0.5f, value)
    }

    @Test
    fun unlockedKnobStillDrags() = runComposeUiTest {
        var value = 0.5f
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        onNodeWithTag("knob").performTouchInput { swipeUp(durationMillis = 300) }

        assertTrue(value > 0.5f, "default knobs keep dragging (was $value)")
    }

    @Test
    fun heldKnobShowsLongLabel() = runComposeUiTest {
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = 0.5f,
                    onValueChange = {},
                    label = "detune",
                    longLabel = "duo detune, ±25 cents",
                    caption = "ψ",
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        onAllNodesWithText("duo detune, ±25 cents").assertCountEquals(0)
        onAllNodesWithText("ψ").assertCountEquals(1)
        onNodeWithTag("knob").performTouchInput { down(center) }
        waitForIdle()
        onAllNodesWithText("duo detune, ±25 cents").assertCountEquals(1)
        onNodeWithTag("knob").performTouchInput { up() }
        waitForIdle()
        onAllNodesWithText("duo detune, ±25 cents").assertCountEquals(0)
    }

    @Test
    fun learnModeTapSelectsTheLockedKnobInsteadOfOpening() = runComposeUiTest {
        var clicks = 0
        val selected = mutableListOf<String>()
        setContent {
            OrpheusTheme {
                CompositionLocalProvider(
                    LocalLearnModeState provides LearnModeState(isActive = true, onSelectControl = { selected += it }),
                ) {
                    RotaryKnob(
                        value = 0.5f,
                        onValueChange = {},
                        controlId = "drums_bd_mod",
                        locked = true,
                        onLockedClick = { clicks++ },
                        modifier = Modifier.testTag("knob"),
                    )
                }
            }
        }

        onNodeWithTag("knob").performClick()

        assertEquals(listOf("drums_bd_mod"), selected, "learn mode selects the knob")
        assertEquals(0, clicks, "the picker stays closed in learn mode")
    }
}

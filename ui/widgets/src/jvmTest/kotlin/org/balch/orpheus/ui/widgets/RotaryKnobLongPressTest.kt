package org.balch.orpheus.ui.widgets

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.input.key.Key
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The knob's long press lives on its LABEL, so the dial's pointer stays drag-only. A still
 * press of the label fires it without moving the value; a long press on the dial itself never
 * fires it; a drag moves the value and never fires it. On TV the label takes focus and a
 * select press fires it.
 */
@OptIn(ExperimentalTestApi::class)
class RotaryKnobLongPressTest {

    @Test
    fun `a still long press on the label fires onLongPress and leaves the value alone`() = runComposeUiTest {
        var longPresses = 0
        var value = 0.5f
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    onLongPress = { longPresses++ },
                    label = "COMPLEXITY",
                    valueFormatter = null,
                )
            }
        }

        onNodeWithText("COMPLEXITY").performTouchInput { longClick() }

        assertEquals(1, longPresses, "one still press of the label is one long press")
        assertEquals(0.5f, value, "a still press does not move the knob")
    }

    @Test
    fun `a long press on the dial does not fire onLongPress`() = runComposeUiTest {
        var longPresses = 0
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = 0.5f,
                    onValueChange = {},
                    onLongPress = { longPresses++ },
                    label = "COMPLEXITY",
                    valueFormatter = null,
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        // The dial is the top of the column; the label sits under it.
        onNodeWithTag("knob").performTouchInput { longClick(Offset(centerX, height * 0.25f)) }

        assertEquals(0, longPresses, "the dial is drag-only")
    }

    @Test
    fun `a drag moves the value and never fires onLongPress`() = runComposeUiTest {
        var longPresses = 0
        var value = 0.5f
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    onLongPress = { longPresses++ },
                    label = null,
                    valueFormatter = null,
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        // Slower than the long-press timeout, so a press that lingered would have fired.
        onNodeWithTag("knob").performTouchInput { swipeUp(durationMillis = 1500) }

        assertEquals(0, longPresses, "a drag is never a long press")
        assertTrue(value > 0.5f, "dragging up raises the value (was $value)")
    }

    @Test
    fun `a select press on the focused label fires onLongPress once`() = runComposeUiTest {
        var longPresses = 0
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = 0.5f,
                    onValueChange = {},
                    onLongPress = { longPresses++ },
                    label = "COMPLEXITY",
                    valueFormatter = null,
                )
            }
        }

        val label = onNodeWithText("COMPLEXITY")
        label.requestFocus()
        label.performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(1, longPresses, "one select press on the focused label is one toggle")
    }
}

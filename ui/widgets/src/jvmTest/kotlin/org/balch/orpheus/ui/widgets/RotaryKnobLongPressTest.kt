package org.balch.orpheus.ui.widgets

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The knob's long press shares one pointer with its value drag. A still press must fire it
 * without moving the value; a drag must move the value and never fire it, even when the
 * pointer stays down past the long-press timeout.
 */
@OptIn(ExperimentalTestApi::class)
class RotaryKnobLongPressTest {

    @Test
    fun `a still long press fires onLongPress and leaves the value alone`() = runComposeUiTest {
        var longPresses = 0
        var value = 0.5f
        setContent {
            OrpheusTheme {
                RotaryKnob(
                    value = value,
                    onValueChange = { value = it },
                    onLongPress = { longPresses++ },
                    // No label or value text, so the node's centre is the dial itself.
                    label = null,
                    valueFormatter = null,
                    modifier = Modifier.testTag("knob"),
                )
            }
        }

        onNodeWithTag("knob").performTouchInput { longClick() }

        assertEquals(1, longPresses, "one still press is one long press")
        assertEquals(0.5f, value, "a still press does not move the knob")
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

        // Slower than the long-press timeout, so a drag that failed to cancel it would fire.
        onNodeWithTag("knob").performTouchInput { swipeUp(durationMillis = 1500) }

        assertEquals(0, longPresses, "a drag is never a long press")
        assertTrue(value > 0.5f, "dragging up raises the value (was $value)")
    }
}

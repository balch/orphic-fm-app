package org.balch.orpheus.djapp

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.playback.SkipDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class VibeKeysTest {

    @Test fun leftIsPrevious() = assertEquals(SkipDirection.PREVIOUS, vibeStepForKey(KeyEvent(Key.DirectionLeft, KeyEventType.KeyDown)))
    @Test fun rightIsNext() = assertEquals(SkipDirection.NEXT, vibeStepForKey(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown)))
    @Test fun keyUpIsIgnored() = assertNull(vibeStepForKey(KeyEvent(Key.DirectionRight, KeyEventType.KeyUp)))
    @Test fun aModifiedArrowIsIgnored() = assertNull(vibeStepForKey(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown, isMetaPressed = true)))
    @Test fun otherKeysAreIgnored() = assertNull(vibeStepForKey(KeyEvent(Key.Spacebar, KeyEventType.KeyDown)))

    private fun VibeStepKeys.feed(vararg events: Pair<Key, KeyEventType>): List<SkipDirection> =
        events.mapNotNull { (key, type) -> stepFor(KeyEvent(key, type)) }

    private val rightDown = Key.DirectionRight to KeyEventType.KeyDown
    private val rightUp = Key.DirectionRight to KeyEventType.KeyUp

    // The OS auto-repeats a held key as more KeyDowns; they must not race through the catalog.
    @Test fun aHeldArrowStepsOnce() =
        assertEquals(listOf(SkipDirection.NEXT), VibeStepKeys().feed(rightDown, rightDown, rightDown, rightDown, rightDown, rightUp))

    @Test fun eachPressAfterAReleaseSteps() =
        assertEquals(listOf(SkipDirection.NEXT, SkipDirection.NEXT), VibeStepKeys().feed(rightDown, rightUp, rightDown, rightUp))

    @Test fun theArrowsAreHeldApart() =
        assertEquals(
            listOf(SkipDirection.NEXT, SkipDirection.PREVIOUS),
            VibeStepKeys().feed(rightDown, Key.DirectionLeft to KeyEventType.KeyDown, rightDown),
        )

    /** Counts arrows that bubble past [inner] to a root handler, the same order the window's onKeyEvent sees. */
    private fun bubbledArrows(caret: Int, key: Key, textField: Boolean): Int {
        var reached = 0
        val focus = FocusRequester()
        val scene = ImageComposeScene(400, 200, Density(1f)) {
            Box(Modifier.onKeyEvent { if (vibeStepForKey(it) != null) reached++; false }) {
                if (textField) {
                    BasicTextField(
                        value = TextFieldValue("hello", TextRange(caret)),
                        onValueChange = {},
                        modifier = Modifier.focusRequester(focus),
                    )
                } else {
                    Box(Modifier.size(40.dp).focusRequester(focus).focusable())
                }
            }
        }
        try {
            scene.render()
            focus.requestFocus()
            scene.render()
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.render()
        } finally {
            scene.close()
        }
        return reached
    }

    @Test fun aFocusedTextFieldKeepsLeftAtItsStart() = assertEquals(0, bubbledArrows(caret = 0, key = Key.DirectionLeft, textField = true))
    @Test fun aFocusedTextFieldKeepsRightAtItsEnd() = assertEquals(0, bubbledArrows(caret = 5, key = Key.DirectionRight, textField = true))
    @Test fun aPlainFocusableLetsArrowsThrough() = assertEquals(1, bubbledArrows(caret = 0, key = Key.DirectionRight, textField = false))
}

package org.balch.orpheus.ui.viz

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What input may reach the panels while they are not fully drawn: the key rule every path shares,
 * and the shape of the overlay that eats the waking gesture.
 *
 * ./gradlew :ui:widgets:jvmTest --tests '*PanelIdleInputTest*' --rerun
 */
@OptIn(InternalComposeUiApi::class)
class PanelIdleInputTest {

    @Test
    fun `a confirm key while the panels are gone only wakes them`() = runTest {
        val fade = faded(0f)
        listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter).forEach { key ->
            assertTrue(
                fade.handlePanelKey(KeyEvent(key, KeyEventType.KeyDown)),
                "$key reached the control under focus while the panels were gone",
            )
        }
        assertEquals(0L, fade.quietMs(), "the swallowed press was not recorded as activity")
    }

    @Test
    fun `the confirm key's own key up goes with it, so no stray release is left behind`() = runTest {
        val fade = faded(0f)
        assertTrue(fade.handlePanelKey(KeyEvent(Key.Enter, KeyEventType.KeyUp)))
    }

    /**
     * The space bar activates a focused control like the other confirm keys, and is deliberately
     * left alone: nothing at the root or the window can tell that the focused node is a text
     * field, and a space missing from a sentence is worse than an unseen button not firing.
     */
    @Test
    fun `the space bar is always delivered, even with the panels gone`() = runTest {
        val fade = faded(0f)
        assertFalse(
            fade.handlePanelKey(KeyEvent(Key.Spacebar, KeyEventType.KeyDown)),
            "the space bar was swallowed, which would drop a space out of someone's typing",
        )
        assertEquals(0L, fade.quietMs(), "the space bar did not wake the panels")
    }

    @Test
    fun `every other key is passed on and still wakes the panels`() = runTest {
        val fade = faded(0f)
        listOf(Key.DirectionLeft, Key.DirectionUp, Key.A, Key.Escape, Key.Tab).forEach { key ->
            assertFalse(
                fade.handlePanelKey(KeyEvent(key, KeyEventType.KeyDown)),
                "$key was swallowed; D-pad moves, synth keys and typing must always be delivered",
            )
        }
        assertEquals(0L, fade.quietMs(), "a passed-on key was not recorded as activity")
    }

    @Test
    fun `nothing is swallowed once the panels are fully drawn`() = runTest {
        val fade = PanelIdleFade { 0L }
        assertFalse(
            fade.handlePanelKey(KeyEvent(Key.Enter, KeyEventType.KeyDown)),
            "a confirm key was swallowed while the panels were fully visible",
        )
    }

    /** The whole point of the overlay rule: barely drawn is not drawn. */
    @Test
    fun `a confirm key is swallowed from the very first frame of the fade`() = runTest {
        val fade = faded(0.99f)
        assertTrue(fade.handlePanelKey(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
    }

    /**
     * Desktop runs two paths: the window hook, then the root modifier if the hook passed the key
     * on. A swallowed key never reaches the second path, and a second notify at the same instant
     * records the same quiet time, so one press behaves like one press either way.
     */
    @Test
    fun `one press seen by both paths behaves exactly like one press`() = runTest {
        val fade = faded(0f)
        val event = KeyEvent(Key.DirectionRight, KeyEventType.KeyDown)
        assertFalse(fade.handlePanelKey(event), "a direction key was swallowed")
        val quietAfterFirst = fade.quietMs()
        val alphaAfterFirst = fade.alpha.value

        assertFalse(fade.handlePanelKey(event), "the second path disagreed with the first")
        assertEquals(quietAfterFirst, fade.quietMs(), "the second path moved the countdown again")
        assertEquals(alphaAfterFirst, fade.alpha.value, "the second path touched the animation")
    }

    @Test
    fun `a fade that is switched off, or absent, never touches a key`() = runTest {
        val fade = faded(0f)
        assertFalse(fade.handlePanelKey(KeyEvent(Key.Enter, KeyEventType.KeyDown), enabled = false))
        val absent: PanelIdleFade? = null
        assertFalse(absent.handlePanelKey(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
    }

    @Test
    fun `with no chrome inside it the stage is blocked whole`() {
        val stage = Rect(0f, 44f, 360f, 700f)
        assertEquals(listOf(stage), wakeBlockers(stage, holes = emptyList()))
        assertEquals(listOf(stage), wakeBlockers(stage, holes = listOf(null, null)))
    }

    /** Tabletop: the header sits between the folded halves, so the block has to skip it. */
    @Test
    fun `a chrome band across the middle leaves a live strip between two blockers`() {
        val stage = Rect(0f, 0f, 400f, 800f)
        val chrome = Rect(0f, 380f, 400f, 420f)
        assertEquals(
            listOf(Rect(0f, 0f, 400f, 380f), Rect(0f, 420f, 400f, 800f)),
            wakeBlockers(stage, listOf(chrome)),
        )
    }

    @Test
    fun `a chrome band on an edge leaves one blocker, and one that covers the stage leaves none`() {
        val stage = Rect(0f, 100f, 400f, 800f)
        assertEquals(
            listOf(Rect(0f, 160f, 400f, 800f)),
            wakeBlockers(stage, listOf(Rect(0f, 40f, 400f, 160f))),
            "a band overlapping the top edge should leave only what is below it",
        )
        assertTrue(
            wakeBlockers(stage, listOf(Rect(0f, 0f, 400f, 900f))).isEmpty(),
            "a band covering the whole stage should leave nothing to block",
        )
    }

    /** The phone bar's dome rises from below the stage: blocked above it and either side, nothing below. */
    @Test
    fun `a dome rising from the bottom edge leaves the stage above it and either side`() {
        val stage = Rect(0f, 44f, 360f, 700f)
        val dome = Rect(148f, 680f, 212f, 772f)
        assertEquals(
            listOf(Rect(0f, 44f, 360f, 680f), Rect(0f, 680f, 148f, 700f), Rect(212f, 680f, 360f, 700f)),
            wakeBlockers(stage, listOf(dome)),
        )
    }

    /** Tabletop with the bar: the header band and the dome, each applied to what the other left. */
    @Test
    fun `a band and a dome together leave both live`() {
        val stage = Rect(0f, 0f, 400f, 800f)
        val band = Rect(0f, 380f, 400f, 420f)
        val dome = Rect(168f, 780f, 232f, 872f)
        assertEquals(
            listOf(
                Rect(0f, 0f, 400f, 380f),
                Rect(0f, 420f, 400f, 780f),
                Rect(0f, 780f, 168f, 800f),
                Rect(232f, 780f, 400f, 800f),
            ),
            wakeBlockers(stage, listOf(band, dome)),
        )
    }

    @Test
    fun `a hole outside the stage changes nothing`() {
        val stage = Rect(0f, 44f, 360f, 700f)
        assertEquals(listOf(stage), wakeBlockers(stage, listOf(Rect(148f, 720f, 212f, 780f))))
        // Touching the edge is not overlapping it.
        assertEquals(listOf(stage), wakeBlockers(stage, listOf(Rect(148f, 700f, 212f, 780f))))
    }

    @Test
    fun `a hole over a corner leaves the stage below it and beside it`() {
        val stage = Rect(0f, 100f, 400f, 800f)
        assertEquals(
            listOf(Rect(0f, 200f, 400f, 800f), Rect(100f, 100f, 400f, 200f)),
            wakeBlockers(stage, listOf(Rect(-50f, 50f, 100f, 200f))),
        )
    }

    private suspend fun faded(alpha: Float): PanelIdleFade =
        PanelIdleFade { 0L }.also { it.alpha.snapTo(alpha) }
}

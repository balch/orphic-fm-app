package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.Box
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The D-pad path: pure cursor maths first, then keys through a real focused bell. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class BellKeysTest {

    private class Calls {
        val log = mutableListOf<String>()
        val strikes = mutableListOf<Triple<Float, Float, Float>>()
        val holds = mutableListOf<Offset>()
        val moves = mutableListOf<Offset>()
        val actions = CowbellPanelActions.EMPTY.copy(
            onStrike = { v, x, y -> strikes += Triple(v, x, y); log += "strike" },
            onHold = { x, y -> holds += Offset(x, y); log += "hold" },
            onMove = { x, y -> moves += Offset(x, y); log += "move" },
            onRelease = { log += "release" },
        )
    }

    private var focus: FocusManager? = null
    private val unhandled = mutableListOf<Key>()

    private fun bellScene(calls: Calls, mode: CowbellMode, cursor: BellKeyCursor = BellKeyCursor()) =
        CowbellScene(200, 300) {
            focus = LocalFocusManager.current
            // A parent sees only the keys the bell leaves unhandled.
            Box(Modifier.fillMaxSize().onKeyEvent { unhandled += it.key; false }) {
                CowbellBell3D(mode = mode, actions = calls.actions, bend = { 0f }, modifier = Modifier.fillMaxSize(), keyCursor = cursor)
            }
        }

    private fun CowbellScene.focusBell() {
        tagged(CowbellBellTag).config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        frame()
    }

    private fun CowbellScene.key(key: Key, type: KeyEventType) {
        scene.sendKeyEvent(KeyEvent(key, type))
        frame()
    }

    private fun CowbellScene.tapKey(key: Key) {
        key(key, KeyEventType.KeyDown)
        key(key, KeyEventType.KeyUp)
    }

    @Test
    fun velocityFromPressGap() {
        val cursor = BellKeyCursor()
        assertEquals(0.45f, cursor.velocityFor(0), 0.001f)
        assertEquals(1f, cursor.velocityFor(100), 0.001f)
        assertEquals(0.725f, BellKeyCursor().apply { velocityFor(0) }.velocityFor(360), 0.01f)
        assertEquals(0.45f, BellKeyCursor().apply { velocityFor(0) }.velocityFor(800), 0.001f)
    }

    @Test
    fun nudgeClampsToRange() {
        val cursor = BellKeyCursor()
        repeat(20) { cursor.nudge(1, 0) }
        assertEquals(1f, cursor.point.x)
        assertEquals(0.5f, cursor.point.y)
        assertTrue(!cursor.nudge(1, 0), "already at the edge")
    }

    @Test
    fun okStrikesAtCursor() {
        val calls = Calls()
        bellScene(calls, CowbellMode.TAP).use { s ->
            s.focusBell()
            s.tapKey(Key.DirectionCenter)
            assertEquals(listOf("strike"), calls.log)
            assertEquals(Offset(0.5f, 0.5f), Offset(calls.strikes.single().second, calls.strikes.single().third))
        }
    }

    @Test
    fun okStrikesInShakeToo() {
        val calls = Calls()
        bellScene(calls, CowbellMode.SHAKE).use { s ->
            s.focusBell()
            s.tapKey(Key.Enter)
            assertEquals(listOf("strike"), calls.log)
        }
    }

    @Test
    fun arrowsMoveTheStrikePointAndUpRaisesStructure() {
        val calls = Calls()
        bellScene(calls, CowbellMode.TAP).use { s ->
            s.focusBell()
            s.tapKey(Key.DirectionRight)
            s.tapKey(Key.DirectionUp)
            s.tapKey(Key.DirectionCenter)
            val (_, x, y) = calls.strikes.single()
            assertEquals(0.55f, x, 0.001f)
            assertEquals(0.45f, y, 0.001f)
        }
    }

    @Test
    fun okRepeatIsIgnored() {
        val calls = Calls()
        bellScene(calls, CowbellMode.TAP).use { s ->
            s.focusBell()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            repeat(3) { s.key(Key.DirectionCenter, KeyEventType.KeyDown) }
            s.key(Key.DirectionCenter, KeyEventType.KeyUp)
            assertEquals(listOf("strike"), calls.log)
        }
    }

    @Test
    fun synthHoldSustainsAndSweeps() {
        val calls = Calls()
        bellScene(calls, CowbellMode.SYNTH).use { s ->
            s.focusBell()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            s.tapKey(Key.DirectionRight)
            s.tapKey(Key.DirectionRight)
            s.key(Key.DirectionCenter, KeyEventType.KeyUp)
            assertEquals(listOf("hold", "move", "move", "release"), calls.log)
            assertEquals(listOf(0.55f, 0.6f), calls.moves.map { it.x })
            assertEquals(Offset(0.5f, 0.5f), calls.holds.single())
        }
    }

    @Test
    fun arrowsWithNothingHeldMoveNothing() {
        val calls = Calls()
        bellScene(calls, CowbellMode.SYNTH).use { s ->
            s.focusBell()
            s.tapKey(Key.DirectionLeft)
            assertEquals(emptyList(), calls.log)
        }
    }

    @Test
    fun focusLossReleasesHeldNote() {
        val calls = Calls()
        bellScene(calls, CowbellMode.SYNTH).use { s ->
            s.focusBell()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            focus!!.clearFocus()
            s.frame()
            assertEquals(listOf("hold", "release"), calls.log)
        }
    }

    /** Cmd-Tab mid-hold: the KeyUp goes to another window and the bell keeps its focus. */
    @Test
    fun windowFocusLossReleasesHeldNote() {
        val calls = Calls()
        var windowFocused by mutableStateOf(true)
        CowbellScene(200, 300) {
            val window = remember {
                object : WindowInfo {
                    override val isWindowFocused get() = windowFocused
                    override val containerSize = IntSize(200, 300)
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CowbellBell3D(mode = CowbellMode.SYNTH, actions = calls.actions, bend = { 0f }, modifier = Modifier.fillMaxSize())
            }
        }.use { s ->
            s.focusBell()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            windowFocused = false
            s.frame()
            assertEquals(listOf("hold", "release"), calls.log)
            // Back in the window, the first press is a fresh hold and not an ignored auto-repeat.
            windowFocused = true
            s.frame()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            assertEquals(listOf("hold", "release", "hold"), calls.log)
        }
    }

    @Test
    fun edgeArrowLetsFocusLeave() {
        val calls = Calls()
        val cursor = BellKeyCursor(Offset(1f, 0.5f))
        bellScene(calls, CowbellMode.TAP, cursor).use { s ->
            s.focusBell()
            unhandled.clear()
            s.key(Key.DirectionRight, KeyEventType.KeyDown)
            assertEquals(listOf(Key.DirectionRight), unhandled)
            s.key(Key.DirectionLeft, KeyEventType.KeyDown)
            assertEquals(listOf(Key.DirectionRight), unhandled, "a nudge inside the range is handled")
        }
    }

    /** On desktop an unhandled Left or Right skips the song, so a held sweep must never leak one. */
    @Test
    fun edgeArrowIsConsumedWhileANoteIsHeld() {
        val calls = Calls()
        val cursor = BellKeyCursor(Offset(1f, 0.5f))
        bellScene(calls, CowbellMode.SYNTH, cursor).use { s ->
            s.focusBell()
            s.key(Key.DirectionCenter, KeyEventType.KeyDown)
            unhandled.clear()
            s.key(Key.DirectionRight, KeyEventType.KeyDown)
            assertEquals(emptyList(), unhandled, "a held sweep at the edge is consumed")
            s.key(Key.DirectionCenter, KeyEventType.KeyUp)
            s.key(Key.DirectionRight, KeyEventType.KeyDown)
            assertEquals(listOf(Key.DirectionRight), unhandled, "with nothing held the edge arrow leaves")
        }
    }
}

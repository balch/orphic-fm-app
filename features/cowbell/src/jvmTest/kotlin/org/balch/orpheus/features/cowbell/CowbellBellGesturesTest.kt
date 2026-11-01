package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.IntSize
import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bell's pointer handling, on a bare bell so nothing but the gesture code is in play. */
class CowbellBellGesturesTest {

    private fun bellScene(
        recording: RecordingActions,
        mode: CowbellMode,
        shown: () -> Boolean = { true },
        steal: () -> Boolean = { false },
    ) = CowbellScene(200, 300) {
        if (shown()) {
            // A parent that can take the pointer before the bell sees it.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (steal()) event.changes.forEach { it.consume() }
                        }
                    }
                },
            ) {
                CowbellBell3D(mode = mode, actions = recording.actions, bend = { 0f }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun tapStrikesOnPress() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val at = s.bell().center
            s.press(at)
            s.release(at)
            assertEquals(listOf("strike", "release"), recording.calls)
            assertEquals(velocityFromPointerSpeed(0f), recording.strikes.single())
        }
    }

    private fun motionScene(recording: RecordingActions, motion: MotionSource?) = CowbellScene(200, 300) {
        CompositionLocalProvider(LocalCowbellMotion provides motion) {
            CowbellBell3D(mode = CowbellMode.TAP, actions = recording.actions, bend = { 0f }, modifier = Modifier.fillMaxSize(), motionOn = true)
        }
    }

    @Test
    fun tapUsesJoltWhenPresent() {
        val recording = RecordingActions()
        val jolts = JoltWindow()
        motionScene(recording, FakeMotion(jolts = jolts)).use { s ->
            // The accelerometer spiked 10 ms before the panel reported the touch.
            jolts.record(s.now - 10, 2.0f)
            s.press(s.bell().center)
            assertEquals(0.45f + 0.55f * joltNorm(2.0f), recording.strikes.single(), 1e-5f)
        }
    }

    @Test
    fun tapIgnoresAJoltOutsideTheWindow() {
        val recording = RecordingActions()
        val jolts = JoltWindow()
        motionScene(recording, FakeMotion(jolts = jolts)).use { s ->
            // Well before the window, and just after the touch: neither counts, so nothing was
            // measured and the pointer's speed decides, as for a still press.
            s.frames(500)
            jolts.record(s.now - 200, 3.0f)
            jolts.record(s.now + 5, 3.0f)
            jolts.record(s.now + 5, 3.0f)
            s.press(s.bell().center)
            assertEquals(0.6f, recording.strikes.single(), 1e-5f)
        }
    }

    @Test
    fun swipeStrikeKeepsAHardPressVelocity() {
        val recording = RecordingActions()
        val jolts = JoltWindow()
        motionScene(recording, FakeMotion(jolts = jolts)).use { s ->
            s.frames(100)
            jolts.record(s.now - 10, 3.0f)
            val start = s.bell().center - Offset(0f, 100f)
            s.press(start)
            // 6 px per 8 ms is 750 dp/s, which alone strikes at 0.8.
            for (i in 1..12) s.move(start + Offset(0f, 6f * i))
            assertTrue(recording.strikes.size > 1, "strikes ${recording.strikes}")
            assertTrue(recording.strikes.all { it > 0.99f }, "strikes ${recording.strikes}")
        }
    }

    @Test
    fun swipeStrikeWithoutMotionUsesSpeedAlone() {
        val recording = RecordingActions()
        motionScene(recording, null).use { s ->
            val start = s.bell().center - Offset(0f, 100f)
            s.press(start)
            for (i in 1..12) s.move(start + Offset(0f, 6f * i))
            assertEquals(0.6f, recording.strikes.first(), 1e-6f)
            assertTrue(recording.strikes.size > 1, "strikes ${recording.strikes}")
            assertEquals(0.8f, recording.strikes.last(), 0.01f)
        }
    }

    @Test
    fun tapFallsBackToSpeed() {
        val recording = RecordingActions()
        motionScene(recording, null).use { s ->
            s.press(s.bell().center)
            assertEquals(0.6f, recording.strikes.single(), 1e-6f)
        }
    }

    @Test
    fun tapFallsBackToSpeedWhenTheSensorsAreMissing() {
        val recording = RecordingActions()
        val jolts = JoltWindow()
        motionScene(recording, FakeMotion(available = false, jolts = jolts)).use { s ->
            jolts.record(s.now - 10, 3.0f)
            s.press(s.bell().center)
            assertEquals(0.6f, recording.strikes.single(), 1e-6f)
        }
    }

    @Test
    fun accessibilityClickStrikesAtHoldVelocity() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val click = s.tagged(CowbellBellTag).config[SemanticsActions.OnClick]
            assertEquals("Strike", click.label)
            click.action!!.invoke()
            assertEquals(listOf(CowbellViewModel.HOLD_STRIKE_VELOCITY), recording.strikes)
        }
    }

    @Test
    fun hoverSpeedSetsVelocity() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val c = s.bell().center
            // 15 px every 8 ms, with the 8 ms before the press, is 60 px in 40 ms: 1500 dp/s.
            for (i in 4 downTo 1) s.move(c - Offset(15f * i, 0f))
            s.move(c)
            s.press(c)
            assertTrue(recording.strikes.single() > 0.95f, "strike ${recording.strikes}")
        }
    }

    @Test
    fun fastDragSwipeStrikes() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val start = s.bell().center - Offset(0f, 100f)
            s.press(start)
            // 12 px per 8 ms is 1500 dp/s; 192 px of travel is six 32 dp steps.
            for (i in 1..16) s.move(start + Offset(0f, 12f * i))
            assertEquals(1 + 6, recording.calls.count { it == "strike" }, "calls ${recording.calls}")
        }
    }

    @Test
    fun synthHoldsUntilRelease() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.SYNTH).use { s ->
            val at = s.bell().center
            s.press(at)
            s.move(at + Offset(5f, 5f))
            s.release(at + Offset(5f, 5f))
            assertEquals(listOf("hold", "move", "release"), recording.calls)
        }
    }

    @Test
    fun rightDragBendsUpThenSpringsBack() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val at = s.bell().center
            s.press(at, PointerButton.Secondary)
            s.move(at - Offset(0f, s.bell().height / 4))
            assertTrue(recording.bends.last() > 0.2f, "bends ${recording.bends}")
            assertTrue("strike" !in recording.calls, "a bend never strikes")
            s.release(at - Offset(0f, s.bell().height / 4), PointerButton.Secondary)
            s.frames(1500)
            assertEquals("bendDone", recording.calls.last())
            // The spring overshoots below zero on its way home.
            assertTrue(recording.bends.any { it < 0f }, "bends ${recording.bends}")
        }
    }

    // Both buttons on the one mouse pointer, as a desktop sends them.
    private fun CowbellScene.mouse(type: PointerEventType, at: Offset, button: PointerButton?, left: Boolean, right: Boolean) =
        pointer(type, at, button, buttons = PointerButtons(isPrimaryPressed = left, isSecondaryPressed = right))

    @Test
    fun heldSynthNoteBendsWithRightButton() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.SYNTH).use { s ->
            val at = s.bell().center
            val up = at - Offset(0f, s.bell().height / 4)
            s.mouse(PointerEventType.Press, at, PointerButton.Primary, left = true, right = false)
            s.mouse(PointerEventType.Press, at, PointerButton.Secondary, left = true, right = true)
            val rightPressedAt = recording.calls.size
            s.mouse(PointerEventType.Move, up, null, left = true, right = true)
            assertTrue(recording.bends.last() > 0.2f, "bends ${recording.bends}")
            assertTrue("move" !in recording.calls.drop(rightPressedAt), "a bend does not move the note: ${recording.calls}")

            s.mouse(PointerEventType.Release, up, PointerButton.Secondary, left = true, right = false)
            s.frames(1500)
            assertEquals("bendDone", recording.calls.last())
            assertTrue("release" !in recording.calls, "the note keeps sounding: ${recording.calls}")

            s.mouse(PointerEventType.Release, up, PointerButton.Primary, left = false, right = false)
            assertEquals("release", recording.calls.last())
            assertEquals(1, recording.calls.count { it == "hold" })
        }
    }

    @Test
    fun rightThenLeftStrikesAndKeepsBending() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val at = s.bell().center
            val up = at - Offset(0f, s.bell().height / 4)
            s.mouse(PointerEventType.Press, at, PointerButton.Secondary, left = false, right = true)
            s.mouse(PointerEventType.Press, at, PointerButton.Primary, left = true, right = true)
            assertEquals(1, recording.calls.count { it == "strike" }, "left strikes under a bend: ${recording.calls}")
            s.mouse(PointerEventType.Move, up, null, left = true, right = true)
            assertTrue(recording.bends.last() > 0.2f, "bends ${recording.bends}")
            assertTrue("move" !in recording.calls, "the motion bends: ${recording.calls}")

            s.mouse(PointerEventType.Release, up, PointerButton.Primary, left = false, right = true)
            assertEquals("release", recording.calls.last())
            s.mouse(PointerEventType.Release, up, PointerButton.Secondary, left = false, right = false)
            s.frames(1500)
            assertEquals("bendDone", recording.calls.last())
        }
    }

    // Multi-touch events in a scene take ComposeScenePointer, an internal compose-ui type.
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    @Test
    fun secondTouchBendsAndFirstKeepsItsHold() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.SYNTH).use { s ->
            val c = s.bell().center
            val pull = c - Offset(0f, s.bell().height / 4)
            fun touches(type: PointerEventType, vararg pointers: ComposeScenePointer) {
                s.scene.sendPointerEvent(type, pointers.toList(), timeMillis = s.now)
                s.frame(8)
            }
            fun finger(id: Long, at: Offset, down: Boolean = true) =
                ComposeScenePointer(PointerId(id), at, down, PointerType.Touch)

            touches(PointerEventType.Press, finger(0, c))
            touches(PointerEventType.Press, finger(0, c), finger(1, c))
            touches(PointerEventType.Move, finger(0, c), finger(1, pull))
            assertTrue(recording.bends.last() > 0.2f, "bends ${recording.bends}")
            touches(PointerEventType.Release, finger(0, c), finger(1, pull, down = false))
            s.frames(1500)
            assertEquals(listOf("hold", "bendDone"), recording.calls.filter { it != "bend" })
            touches(PointerEventType.Release, finger(0, c, down = false))
            assertEquals("release", recording.calls.last())
        }
    }

    @Test
    fun gestureCancelledMidHoldReleases() {
        val recording = RecordingActions()
        var shown by mutableStateOf(true)
        bellScene(recording, CowbellMode.SYNTH, shown = { shown }).use { s ->
            s.press(s.bell().center)
            shown = false
            s.frame()
            assertEquals(listOf("hold", "release"), recording.calls)
        }
    }

    @Test
    fun gestureCancelledMidBendEndsTheBend() {
        val recording = RecordingActions()
        var shown by mutableStateOf(true)
        bellScene(recording, CowbellMode.TAP, shown = { shown }).use { s ->
            val at = s.bell().center
            s.press(at, PointerButton.Secondary)
            s.move(at - Offset(0f, 40f))
            shown = false
            s.frame()
            assertEquals("bendDone", recording.calls.last(), "calls ${recording.calls}")
        }
    }

    @Test
    fun windowLosingFocusMidHoldReleasesAndGainingItDoesNot() {
        val recording = RecordingActions()
        var focused by mutableStateOf(false)
        CowbellScene(200, 300) {
            val window = remember {
                object : WindowInfo {
                    override val isWindowFocused get() = focused
                    override val containerSize = IntSize(200, 300)
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CowbellBell3D(mode = CowbellMode.SYNTH, actions = recording.actions, bend = { 0f }, modifier = Modifier.fillMaxSize())
            }
        }.use { s ->
            // The click that focuses the window keeps its hold.
            s.press(s.bell().center)
            focused = true
            s.frame()
            assertEquals(listOf("hold"), recording.calls)

            focused = false
            s.frame()
            assertEquals(listOf("hold", "release"), recording.calls)
        }
    }

    @Test
    fun pointerTakenElsewhereMidHoldReleases() {
        val recording = RecordingActions()
        var steal by mutableStateOf(false)
        bellScene(recording, CowbellMode.SYNTH, steal = { steal }).use { s ->
            val at = s.bell().center
            s.press(at)
            steal = true
            s.move(at + Offset(4f, 4f))
            assertEquals(listOf("hold", "release"), recording.calls)
            // The rest of the stolen press is ignored.
            s.release(at)
            assertEquals(listOf("hold", "release"), recording.calls)
        }
    }

    /** Records what the gestures hand the fill. */
    private class RecordingShake : ShakeInput {
        val tilts = mutableListOf<Pair<Float, Float>>()
        val kicks = mutableListOf<Pair<Float, Float>>()
        val calls = mutableListOf<String>()
        val bends = mutableListOf<Float>()
        override fun pointerTilt(x: Float, y: Float) { tilts += x to y }
        override fun kick(dx: Float, dy: Float) { kicks += dx to dy }
        override fun plunge(bend: Float) { calls += "plunge"; bends += bend }
        override fun release(bend: Float) { calls += "release"; bends += bend }
    }

    /** The bare gesture modifier on a box, so only the gesture code and a recording seam are in play. */
    private fun shakeScene(recording: RecordingActions, shake: RecordingShake, mode: CowbellMode = CowbellMode.SHAKE) =
        CowbellScene(200, 300) {
            val actions = remember { mutableStateOf(recording.actions) }
            val modeState = remember(mode) { mutableStateOf(mode) }
            val bounds = remember { mutableStateOf(Rect(40f, 60f, 160f, 240f)) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(CowbellBellTag)
                    .cowbellBellGestures(actions, modeState, bounds, restartKey = 0, onStruck = { _, _ -> }, shake = shake),
            )
        }

    @Test
    fun desktopShakePressKicksNotStrikes() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val at = s.bell().center + Offset(50f, 0f)
            s.press(at)
            s.release(at)
            assertTrue("strike" !in recording.calls, "calls ${recording.calls}")
            assertEquals(1, shake.kicks.size, "kicks ${shake.kicks}")
            // Toward the press point: right of the bell's centre, so a kick to +x.
            assertTrue(shake.kicks.single().first > 5f, "kick ${shake.kicks}")
            assertEquals(0f, shake.kicks.single().second, 1f)
        }
    }

    @Test
    fun touchShakePressKicksNotStrikes() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val at = s.bell().center - Offset(0f, 60f)
            s.pointer(PointerEventType.Press, at, PointerButton.Primary, pointerType = PointerType.Touch)
            s.pointer(PointerEventType.Release, at, PointerButton.Primary, pointerType = PointerType.Touch)
            assertTrue("strike" !in recording.calls, "calls ${recording.calls}")
            assertEquals(1, shake.kicks.size, "kicks ${shake.kicks}")
            assertTrue(shake.kicks.single().second < -5f, "kick ${shake.kicks}")
        }
    }

    @Test
    fun touchShakeFlingKicksAlongTheFinger() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val at = s.bell().center
            s.pointer(PointerEventType.Press, at, PointerButton.Primary, pointerType = PointerType.Touch)
            s.pointer(PointerEventType.Move, at + Offset(20f, 0f), PointerButton.Primary, pointerType = PointerType.Touch)
            s.pointer(PointerEventType.Move, at + Offset(50f, 0f), PointerButton.Primary, pointerType = PointerType.Touch)
            s.pointer(PointerEventType.Release, at + Offset(50f, 0f), PointerButton.Primary, pointerType = PointerType.Touch)
            assertTrue("strike" !in recording.calls, "calls ${recording.calls}")
            // The press's push, then one kick per move, each the finger's travel scaled up.
            assertEquals(listOf(20f * TOUCH_KICK_GAIN to 0f, 30f * TOUCH_KICK_GAIN to 0f), shake.kicks.drop(1), "kicks ${shake.kicks}")
        }
    }

    @Test
    fun tapPressStillStrikesWithAShakeSeam() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake, CowbellMode.TAP).use { s ->
            s.press(s.bell().center)
            assertEquals(1, recording.calls.count { it == "strike" })
            assertTrue(shake.kicks.isEmpty())
        }
    }

    @Test
    fun desktopShakePointerTilts() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val bell = s.bell()
            s.move(Offset(bell.right - 0.5f, bell.center.y))
            val (x, y) = shake.tilts.last()
            assertEquals(1f, x, 0.02f)
            assertEquals(0f, y, 0.02f)
            s.move(Offset(bell.left + 0.5f, bell.top + 0.5f))
            assertEquals(-1f, shake.tilts.last().first, 0.02f)
            assertEquals(-1f, shake.tilts.last().second, 0.02f)
        }
    }

    @Test
    fun hoverMovementKicksInShake() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val c = s.bell().center
            s.move(c)
            s.move(c + Offset(12f, -4f))
            val (dx, dy) = shake.kicks.last()
            assertEquals(12f, dx, 0.5f)
            assertEquals(-4f, dy, 0.5f)
            assertTrue("strike" !in recording.calls)
        }
    }

    @Test
    fun shakeDragNeverStrikesOrMovesANote() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val start = s.bell().center - Offset(0f, 100f)
            s.press(start)
            for (i in 1..16) s.move(start + Offset(0f, 12f * i))
            s.release(start + Offset(0f, 192f))
            assertTrue("strike" !in recording.calls && "move" !in recording.calls, "calls ${recording.calls}")
        }
    }

    @Test
    fun bendDrivesPlunger() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val at = s.bell().center
            val low = at + Offset(0f, 200f)
            s.press(at, PointerButton.Secondary)
            s.move(low)
            s.release(low, PointerButton.Secondary)
            assertEquals("plunge", shake.calls.first())
            assertEquals(-1f, shake.bends.last(), 1e-4f)
            assertEquals(listOf("plunge", "release"), shake.calls.distinct())
            assertEquals("release", shake.calls.last())
            assertEquals(-1f, shake.bends.last(), 1e-4f)
        }
    }

    @Test
    fun bendUpDrivesThePlungerTheOtherWay() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        shakeScene(recording, shake).use { s ->
            val at = s.bell().center
            val top = at - Offset(0f, 200f)
            s.press(at, PointerButton.Secondary)
            s.move(top)
            s.release(top, PointerButton.Secondary)
            assertTrue(shake.bends.first() > 0f)
            assertEquals(1f, shake.bends.last(), 1e-4f)
        }
    }

    @Test
    fun aCancelledBendLetsGoOfThePlunger() {
        val recording = RecordingActions()
        val shake = RecordingShake()
        var shown by mutableStateOf(true)
        CowbellScene(200, 300) {
            if (shown) {
                val actions = remember { mutableStateOf(recording.actions) }
                val modeState = remember { mutableStateOf(CowbellMode.SHAKE) }
                val bounds = remember { mutableStateOf(Rect(40f, 60f, 160f, 240f)) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(CowbellBellTag)
                        .cowbellBellGestures(actions, modeState, bounds, restartKey = 0, onStruck = { _, _ -> }, shake = shake),
                )
            }
        }.use { s ->
            val at = s.bell().center
            s.press(at, PointerButton.Secondary)
            s.move(at + Offset(0f, 60f))
            shown = false
            s.frame()
            assertEquals(0f, shake.bends.last(), 0f)
            assertEquals("plunge", shake.calls.last())
        }
    }
}

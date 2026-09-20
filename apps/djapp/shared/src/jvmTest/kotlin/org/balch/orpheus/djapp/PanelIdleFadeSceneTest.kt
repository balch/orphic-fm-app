package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.PanelFadeInMs
import org.balch.orpheus.ui.viz.PanelFadeOutMs
import org.balch.orpheus.ui.viz.PanelIdleFade
import org.balch.orpheus.ui.viz.PanelIdleFadeWatcher
import org.balch.orpheus.ui.viz.PanelIdleTimeoutMs
import org.balch.orpheus.ui.viz.PanelWakeOverlay
import org.balch.orpheus.ui.viz.VizStage
import org.balch.orpheus.ui.viz.panelIdleFade
import org.balch.orpheus.ui.viz.vizStage
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The idle fade as it actually renders and as it actually takes input, on the layout shape every
 * DJ layout shares: chrome, a stage holding the content panels, chrome.
 *
 * Time is virtual on both clocks at once, a [TestCoroutineScheduler] for the watcher's `delay`
 * and `ImageComposeScene.render(nanoTime)` for the animation's frames, so three seconds of idle
 * plus six tenths of fade cost nothing in wall time.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*PanelIdleFadeSceneTest*' --rerun
 */
class PanelIdleFadeSceneTest {

    @Test
    fun `the panels go when the clock runs out and the chrome stays`() {
        val d = driver()
        d.stepUntil(0f)

        val px = d.pixels()
        assertEquals(HeaderColour, px[Width / 2, HeaderPx / 2], "the header faded with the panels")
        assertEquals(NavColour, px[Width / 2, Height - NavPx / 2], "the nav faded with the panels")
        assertTrue(
            px[Width / 2, Height / 2] == VoidColour,
            "the panel is still painted: ${px[Width / 2, Height / 2]}",
        )
        d.close()
    }

    @Test
    fun `the first click while hidden only wakes the panels and the second one lands`() {
        val d = driver()
        d.stepUntil(0f)
        assertEquals(0, d.clicks, "something clicked the control before the test did")

        d.click(Offset(Width / 2f, Height / 2f))
        assertEquals(0, d.clicks, "the waking click reached the control under it")

        // Back up, and the overlay with it. Not an exact match: the pointer that woke them also
        // left the control hovered, and the hover indication tints it very slightly.
        d.stepUntil(1f)
        assertNear(
            PanelColour,
            d.pixels()[Width / 2, Height / 2],
            "the panel never came back",
        )

        d.click(Offset(Width / 2f, Height / 2f))
        assertEquals(1, d.clicks, "a click on visible panels did not reach the control")
        d.close()
    }


    @Test
    fun `hover neither holds the panels up nor brings them back`() {
        val d = driver()
        var fadeStartedAt = -1L
        // Ten seconds of the mouse crossing the window, one report per frame.
        repeat((10_000L / FrameMs).toInt()) {
            d.hover(Offset(Width / 2f, Height / 3f + (it % 30)))
            if (fadeStartedAt < 0 && d.alpha < 1f) fadeStartedAt = d.nowMs
        }

        assertTrue(
            fadeStartedAt in PanelIdleTimeoutMs..(PanelIdleTimeoutMs + 3 * FrameMs),
            "the fade did not start on time under hover: ${fadeStartedAt}ms",
        )
        assertTrue(d.alpha <= GoneAlpha, "hover brought the panels back: ${d.alpha}")
        assertEquals(VoidColour, d.pixels()[Width / 2, Height / 2], "the panels are still painted")
        d.close()
    }

    @Test
    fun `a press wakes the panels`() {
        val d = driver()
        d.stepUntil(0f)
        d.press(Centre)
        d.release(Centre)
        d.stepUntil(1f)
        assertTrue(d.alpha > 0.99f, "a press did not wake the panels: ${d.alpha}")
        d.close()
    }

    @Test
    fun `a scroll wakes the panels`() {
        val d = driver()
        d.stepUntil(0f)
        d.scroll(Centre)
        d.stepUntil(1f)
        assertTrue(d.alpha > 0.99f, "a scroll did not wake the panels: ${d.alpha}")
        d.close()
    }

    /**
     * Compose routes key events along the focus path, so the root observer only sees them once
     * something in the window holds focus, which is always true on a television (DjTvTopBar
     * requests focus on Play at startup) and true on desktop from the first interaction onwards.
     * The scene focuses its control to stand in for that. See the report's concerns for the gap
     * this leaves on a desktop window nobody has touched yet.
     */
    @Test
    fun `a key down wakes the panels once something holds focus`() {
        val d = driver(focusControl = true)
        d.stepUntil(0f)
        d.keyDown()
        d.stepUntil(1f)
        assertTrue(d.alpha > 0.99f, "a key did not wake the panels: ${d.alpha}")
        d.close()
    }

    @Test
    fun `a five second drag holds the panels up and the countdown starts at the release`() {
        val d = driver()
        d.press(Centre)
        repeat((5_000L / FrameMs).toInt()) {
            d.moveTo(Offset(Width / 2f, Height / 2f + (it % 20)))
            assertTrue(d.alpha > 0.99f, "the panels moved mid-drag at ${d.nowMs}ms: ${d.alpha}")
        }
        d.release(Centre)
        val release = d.nowMs

        repeat(((PanelIdleTimeoutMs - 200) / FrameMs).toInt()) { d.step(FrameMs) }
        assertTrue(
            d.alpha > 0.99f,
            "the fade started before three seconds past the release: ${d.alpha}",
        )

        d.stepUntil(0f)
        assertTrue(
            d.nowMs - release >= PanelIdleTimeoutMs,
            "the countdown did not run from the release",
        )
        d.close()
    }

    @Test
    fun `a drag that begins while hidden wakes the panels and reaches nothing`() {
        val d = driver()
        d.stepUntil(0f)

        d.press(Centre)
        repeat(6) { d.moveTo(Offset(Width / 2f + it * 4, Height / 2f)) }
        d.release(Offset(Width / 2f + 24, Height / 2f))
        d.stepUntil(1f)

        assertEquals(0, d.clicks, "the waking drag reached the control under it")
        assertTrue(d.alpha > 0.99f, "the waking drag did not bring the panels back: ${d.alpha}")
        d.close()
    }

    /**
     * The common desktop case now that hover does not wake: the panels are part way through the
     * 600 ms fade, still faintly drawn, and a click lands on a control the user can barely see.
     */
    @Test
    fun `a click part way through the fade out only wakes the panels`() {
        val d = driver()
        while (d.alpha > 0.5f) d.step(FrameMs)
        assertTrue(d.alpha < 1f && d.alpha > 0f, "the fade never reached its middle: ${d.alpha}")

        d.click(Centre)
        assertEquals(0, d.clicks, "a click on a half faded control reached it")
        d.stepUntil(1f)
        assertTrue(d.alpha > 0.99f, "the click did not bring the panels back: ${d.alpha}")

        d.click(Centre)
        assertEquals(1, d.clicks, "a click on fully drawn panels did not reach the control")
        d.close()
    }

    /**
     * The overlay now covers the whole fade, so it is worth pinning how long it can eat a click
     * for. A double click's second half lands around 200 ms after its first; the panels have to
     * be back before that, and they are, because waking is one fade in and nothing more.
     */
    @Test
    fun `the panels come back inside the fade in, so the overlay cannot linger`() {
        val d = driver()
        d.stepUntil(0f)

        d.click(Centre)
        val woke = d.nowMs
        d.stepUntil(1f)
        assertTrue(
            d.nowMs - woke <= PanelFadeInMs + 3 * FrameMs,
            "waking took ${d.nowMs - woke}ms, longer than the ${PanelFadeInMs}ms fade in",
        )

        d.click(Centre)
        assertEquals(1, d.clicks, "the click after the fade in did not reach the control")
        d.close()
    }

    /**
     * A confirm key activates whatever holds focus, which while the panels are gone is a control
     * nobody can see. It wakes them and goes no further; the next press works normally.
     */
    @Test
    fun `a confirm key while hidden wakes the panels without activating the control`() {
        val d = driver(focusControl = true)
        d.stepUntil(0f)

        d.confirmKey()
        assertEquals(0, d.clicks, "the waking confirm key activated the control under focus")
        d.stepUntil(1f)
        assertTrue(d.alpha > 0.99f, "a confirm key did not wake the panels: ${d.alpha}")

        d.confirmKey()
        assertEquals(1, d.clicks, "a confirm key on visible panels did not reach the control")
        d.close()
    }

    @Test
    fun `a right click while hidden also only wakes`() {
        val d = driver()
        d.stepUntil(0f)
        d.press(Centre, PointerButton.Secondary)
        d.release(Centre, PointerButton.Secondary)
        d.stepUntil(1f)
        assertEquals(0, d.clicks, "a right click reached the control under it")
        assertTrue(d.alpha > 0.99f, "a right click did not wake the panels: ${d.alpha}")
        d.close()
    }

    private fun driver(focusControl: Boolean = false): Driver = Driver(focusControl)

    /** Near, not equal: a hovered control carries a faint indication tint over its fill. */
    private fun assertNear(expected: Color, actual: Color, what: String) {
        val drift = maxOf(
            abs(expected.red - actual.red),
            abs(expected.green - actual.green),
            abs(expected.blue - actual.blue),
        )
        assertTrue(drift < 0.1f, "$what: expected about $expected, got $actual")
    }

    /**
     * One scene plus the two clocks that drive it. [nowMs] feeds the scheduler, the frame clock
     * and the holder's own throttle, so all three agree on what time it is.
     */
    private class Driver(focusControl: Boolean) {
        var nowMs = 0L
        var clicks = 0
        private val scheduler = TestCoroutineScheduler()
        private val stage = VizStage()
        private val fade = PanelIdleFade { nowMs }
        private val scene: ImageComposeScene

        init {
            scene = ImageComposeScene(
                width = Width,
                height = Height,
                density = Density(1f),
                coroutineContext = UnconfinedTestDispatcher(scheduler),
            ) {
                OrpheusTheme {
                    CompositionLocalProvider(
                        LocalVizStage provides stage,
                        LocalPanelIdleFade provides fade,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(VoidColour)
                                .panelActivityObserver(fade, enabled = true),
                        ) {
                            PanelIdleFadeWatcher(fade, enabled = true)
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.height(HeaderPx.dp).fillMaxWidth().background(HeaderColour))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .vizStage(stage)
                                        .panelIdleFade(fade),
                                ) {
                                    // Stands in for a knob: the point is whether input reaches it.
                                    val focus = remember { FocusRequester() }
                                    if (focusControl) {
                                        LaunchedEffect(Unit) { focus.requestFocus() }
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(PanelColour)
                                            .focusRequester(focus)
                                            .clickable { clicks++ },
                                    )
                                }
                                Box(Modifier.height(NavPx.dp).fillMaxWidth().background(NavColour))
                            }
                            PanelWakeOverlay(fade, stage, enabled = true)
                        }
                    }
                }
            }
            scene.render(0)
        }

        fun step(ms: Long) {
            nowMs += ms
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
            scene.render(nowMs * 1_000_000L)
        }

        /**
         * Steps frames until the fade has settled at [target], or gives up loudly. One extra
         * frame at the end: the frame that lands on the target value is drawn by the next one.
         */
        fun stepUntil(target: Float) {
            repeat(MaxFrames) {
                if (abs(fade.alpha.value - target) < 0.001f) {
                    step(FrameMs)
                    return
                }
                step(FrameMs)
            }
            throw AssertionError("alpha stuck at ${fade.alpha.value}, wanted $target")
        }

        val alpha: Float get() = fade.alpha.value

        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            step(FrameMs)
        }

        /** The mouse crossing the window with no button down. Not input. */
        fun hover(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            step(FrameMs)
        }

        fun press(at: Offset, button: PointerButton = PointerButton.Primary) {
            scene.sendPointerEvent(PointerEventType.Press, at, button = button)
            step(FrameMs)
        }

        fun moveTo(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            step(FrameMs)
        }

        fun release(at: Offset, button: PointerButton = PointerButton.Primary) {
            scene.sendPointerEvent(PointerEventType.Release, at, button = button)
            step(FrameMs)
        }

        fun scroll(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, 1f))
            step(FrameMs)
        }

        /** Space bar down: what a keyboard and a remote's select both look like to Compose. */
        @OptIn(InternalComposeUiApi::class)
        fun keyDown() {
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            step(FrameMs)
        }

        /**
         * A whole confirm press. Foundation's clickable fires one of these on the key up. Enter,
         * not the space bar: the space bar is deliberately never swallowed, so that typing keeps
         * working, and a whole space press here would land on the control.
         */
        @OptIn(InternalComposeUiApi::class)
        fun confirmKey() {
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            step(FrameMs)
        }

        fun pixels() = Image.makeFromEncoded(scene.render(nowMs * 1_000_000L).encodeToData()!!.bytes)
            .toComposeImageBitmap()
            .toPixelMap()

        fun close() = scene.close()
    }

    private companion object {
        const val Width = 360
        const val Height = 640
        const val HeaderPx = 48
        const val NavPx = 80
        const val FrameMs = 16L

        /** What the eye reads as gone. The overlay's own rule is stricter: anything under 1f. */
        const val GoneAlpha = 0.05f

        val VoidColour = Color(0xFF14141F)
        val HeaderColour = Color(0xFF2E7BE0)
        val NavColour = Color(0xFFE0A02E)
        val PanelColour = Color(0xFF3FC08A)
        val Centre = Offset(Width / 2f, Height / 2f)

        /** Generous: the longest wait here is the idle timeout plus a full fade. */
        val MaxFrames = ((PanelIdleTimeoutMs + PanelFadeOutMs * 2) / FrameMs).toInt() + 30
    }
}

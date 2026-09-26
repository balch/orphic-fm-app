package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dock's play/pause, the bottom bar's centre dome: focused at launch, so the desktop keyboard
 * and the TV's D-pad work before anything else. Drives the real [DjAppTvChrome], which arrives a
 * frame after launch as DjLayoutBox brings in the dock, on a standard test dispatcher.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockDomeTest*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
class DockDomeTest {
    private val vibe = "Space & Drift"

    private inner class Dock(
        private val tv: Boolean,
        paused: Boolean = false,
        private val arriveLater: Boolean = true,
        private val ringSize: Dp = TvDockDomeRingSize,
    ) {
        var toggles = 0
        var nexts = 0
        var previouses = 0
        val steps = mutableListOf<SkipDirection>()
        private val keys = VibeStepKeys()
        private val scheduler = TestCoroutineScheduler()
        private var now = 0L
        private val docked = mutableStateOf(!arriveLater)

        // DjAppTvChrome provides it; nothing runs its idle fade here unless a test does.
        val region = TvFocusRegionHolder()

        private val pulsar: PulsarFeature = run {
            val base = PulsarViewModel.previewFeature()
            object : PulsarFeature by base {
                override val stateFlow: StateFlow<PulsarUiState> =
                    MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
                override val vibeNavFlow: StateFlow<VibeNavState> =
                    MutableStateFlow(VibeNavState(vibe, "Dog House", "Stay Asleep", progress = 0.62f))
                override val actions: PulsarPanelActions =
                    base.actions.copy(nextVibe = { nexts++ }, previousVibe = { previouses++ })
            }
        }

        val scene = ImageComposeScene(1280, 720, Density(1f), StandardTestDispatcher(scheduler)) {
            OrpheusTheme {
                // Stands in for the desktop window's onKeyEvent: it sees what the focused element passes on.
                Box(Modifier.fillMaxSize().onKeyEvent { event -> keys.stepFor(event)?.also { steps += it } != null }) {
                    if (docked.value) DjAppTvChrome(
                        tvHardware = tv,
                        domeRingSize = ringSize,
                        barGlass = false,
                        vizHidesPanelsWhenIdle = false,
                        focusRegion = region,
                        vizFeature = VizViewModel.previewFeature(),
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = { toggles++ },
                        dockablePanels = largeScreenPanels(),
                        dockedPanels = listOf(PulsarTab, DjTab),
                        activeSheet = null,
                        tabs = djTabs,
                        onToggleDocked = {},
                        onActiveSheetChange = {},
                        stage = {},
                    )
                }
            }
        }

        fun frame(): Image {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(now * 1_000_000)
        }

        fun pixels(): PixelMap = Image.makeFromEncoded(frame().encodeToData()!!.bytes).toComposeImageBitmap().toPixelMap()

        /**
         * Drawn pixels on the focus mark's circle around the ring, at its sides only: the name sits
         * under the ring, and the bar's region border runs along the bar's edges.
         */
        fun markPixels(): Int {
            val px = pixels()
            val centre = ringCentre
            val radius = domeFocusRadius(ringSize).value
            var count = 0
            for (y in (centre.y - 12).toInt()..(centre.y + 12).toInt()) {
                for (x in (centre.x - radius - 3).toInt()..(centre.x + radius + 3).toInt()) {
                    val r = hypot(x + 0.5f - centre.x, y + 0.5f - centre.y)
                    if (r >= radius - 1.5f && r <= radius + 1.5f && px[x, y].alpha > 0.3f) count++
                }
            }
            return count
        }

        init {
            // The dock arrives a frame after launch, once DjLayoutBox has measured the window.
            frame()
            docked.value = true
            repeat(3) { frame() }
        }

        fun press(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            frame()
        }

        /**
         * A pointer event on the frame clock, so a drag's velocity is its dp per 16ms frame, not wall
         * time. A press and a release name their button, as a real one does: only that turns the
         * platform to touch mode.
         */
        fun pointer(type: PointerEventType, at: Offset, pointer: PointerType = PointerType.Mouse) {
            val button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null
            scene.sendPointerEvent(type, at, timeMillis = now, type = pointer, button = button)
            frame()
        }

        fun tap(at: Offset, pointer: PointerType = PointerType.Mouse) {
            pointer(PointerEventType.Press, at, pointer)
            pointer(PointerEventType.Release, at, pointer)
        }

        /** Drags [dx] dp sideways from [from] in 4dp frames, 250 dp/s: only a far drag commits. */
        fun drag(from: Offset, dx: Int, release: Boolean = true) {
            pointer(PointerEventType.Press, from)
            val sign = if (dx > 0) 1 else -1
            (4..abs(dx) step 4).forEach { pointer(PointerEventType.Move, from + Offset((sign * it).toFloat(), 0f)) }
            if (release) pointer(PointerEventType.Release, from + Offset(dx.toFloat(), 0f))
        }

        private fun collect(root: SemanticsNode): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                out += node
                node.children.forEach(::walk)
            }
            walk(root)
            return out
        }

        val nodes: List<SemanticsNode> get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
        private val unmerged: List<SemanticsNode> get() = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }

        val focused: SemanticsNode?
            get() = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

        /** The transport's node: its description names the vibe. Its box is the padded ring and the name. */
        val dome: SemanticsNode?
            get() = nodes.firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(", $vibe") }
            }

        val domeBox: Rect get() = assertNotNull(dome).bounds

        /** The ring as laid out: from the node's top padding to the name under it. */
        val measuredRing: Float
            get() = assertNotNull(
                unmerged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == vibe } },
                "no name under the dome",
            ).positionInRoot.y - domeBox.top - TransportPadding.value

        /** The ring's centre: 4dp under the node's top, then half the ring. */
        val ringCentre: Offset get() = domeBox.let { Offset(it.center.x, it.top + TransportPadding.value + ringSize.value / 2) }

        /** A Play or Pause plate of its own, as TV hardware's top bar once had. */
        val plate: SemanticsNode?
            get() = unmerged.firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Play" || it.text == "Pause" }
            }

        fun close() = scene.close()
    }

    private fun SemanticsNode.reads(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString()

    private val SemanticsNode.bounds: Rect get() = Rect(positionInRoot, size.toSize())

    // A focus circle outside the ring while the dome holds focus, riding the dock's focus idle fade.
    @Test
    fun theFocusedDomeWearsAMarkThatFadesWithTheFocusRegion() {
        listOf(TvDockDomeRingSize, DockDomeRingSize).forEach { ring ->
            val dock = Dock(tv = false, ringSize = ring)
            try {
                assertNotNull(dock.focused, "sanity: the dome took the launch focus ($ring)")
                assertEquals(ring.value, dock.measuredRing, 0.5f, "sanity: the ring is not $ring")
                val bounds = dock.domeBox
                assertTrue(dock.markPixels() > 40, "a focused dome drew no mark while the region was awake ($ring)")
                runBlocking { dock.region.alpha.snapTo(0f) }
                assertEquals(0, dock.markPixels(), "the mark outlived the idle fade ($ring)")
                // The mark's wrapper adds nothing a screen reader or a finger meets.
                val after = assertNotNull(dock.dome)
                assertEquals(listOf("Pause, $vibe"), after.config.getOrNull(SemanticsProperties.ContentDescription))
                assertEquals(bounds, after.bounds, "the mark moved the dome ($ring)")
            } finally {
                dock.close()
            }
        }
    }

    // Focus-visible: a pointer press leaves the dome focused but hides its mark; a key brings it back.
    @Test
    fun aPointerPressHidesTheMarkAndAKeyBringsItBack() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val dock = Dock(tv = false)
            try {
                assertTrue(dock.markPixels() > 40, "sanity: the launch focus shows the mark ($type)")
                dock.tap(dock.ringCentre, type)
                assertEquals(1, dock.toggles, "a $type tap on the dome")
                val focused = assertNotNull(dock.focused, "the dome lost focus to a $type press")
                assertTrue("Play" in focused.reads() || "Pause" in focused.reads(), "focus left the dome: ${focused.reads()}")
                assertEquals(0, dock.markPixels(), "a $type press left the mark showing")
                dock.press(Key.DirectionRight)
                assertEquals(listOf(SkipDirection.NEXT), dock.steps, "the arrow never reached the window ($type)")
                assertTrue(dock.markPixels() > 40, "a key reaching the dome did not bring the mark back ($type)")
                assertEquals(1, dock.toggles, "the arrow toggled playback ($type)")
                // The key took the platform back to keyboard mode, so the next press leaves it again.
                dock.tap(dock.ringCentre, type)
                assertEquals(0, dock.markPixels(), "a second $type press after a key left the mark showing")
            } finally {
                dock.close()
            }
        }
    }

    // Focus coming back by the keyboard shows the mark, though the last press on the dome hid it.
    @Test
    fun focusReturningByTabShowsTheMarkAfterAClick() {
        val dock = Dock(tv = false)
        try {
            dock.tap(dock.ringCentre)
            assertEquals(0, dock.markPixels(), "sanity: the click hid the mark")
            dock.press(Key.Tab)
            val away = assertNotNull(dock.focused, "Tab left nothing focused")
            assertTrue("Play" !in away.reads() && "Pause" !in away.reads(), "sanity: Tab did not move focus off the dome")
            assertEquals(0, dock.markPixels(), "the mark shows without focus")
            dock.press(Key.Tab, shift = true)
            val back = assertNotNull(dock.focused, "Shift+Tab left nothing focused")
            assertTrue("Pause" in back.reads(), "sanity: Shift+Tab did not bring focus back to the dome: ${back.reads()}")
            assertTrue(dock.markPixels() > 40, "focus arriving by the keyboard did not show the mark")
            // Drags still work after all that.
            dock.drag(dock.ringCentre, 48)
            assertEquals(1, dock.nexts, "a drag right after the keyboard came back")
        } finally {
            dock.close()
        }
    }

    private operator fun PixelMap.get(at: Offset): Color = this[at.x.toInt(), at.y.toInt()]

    private fun Color.near(other: Color) =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) + abs(alpha - other.alpha) < 0.03f

    // Launch focus leaves the dome as it was, the mark aside; hover and press lighten a circle on
    // the ring, never darken it and never reach the name under it or the node's square corners.
    @Test
    fun focusLeavesTheBigDomeAloneAndHoverAndPressLightenItInACircle() {
        // Paused, so the name holds still rather than scrolling in its lane.
        val dock = Dock(tv = false, paused = true, ringSize = DockDomeRingSize)
        try {
            assertNotNull(dock.focused, "sanity: the dome took the launch focus")
            val box = dock.domeBox
            val centre = dock.ringCentre
            // On the dome's body below the glyph, in the node's corner outside the ring and its mark,
            // and on the name's line under the ring.
            val body = Offset(centre.x, centre.y + DockDomeRingSize.value * 0.28f)
            val corner = Offset(box.left + 4f, box.top + 6f)
            val name = Offset(centre.x, box.bottom - 12f)
            val focusedLook = dock.pixels()
            dock.press(Key.Tab)
            assertTrue(vibe !in assertNotNull(dock.focused).reads(), "sanity: Tab did not move focus off the dome")
            val rest = dock.pixels()
            assertTrue(focusedLook[body].near(rest[body]), "focus changed the dome: ${focusedLook[body]} vs ${rest[body]}")

            dock.pointer(PointerEventType.Move, centre)
            repeat(12) { dock.frame() }
            val hovered = dock.pixels()
            assertTrue(hovered[body].luminance() > rest[body].luminance() + 0.01f, "hover did not lighten the dome: ${hovered[body]} vs ${rest[body]}")
            assertTrue(hovered[corner].near(rest[corner]), "hover reached the node's square corner")

            dock.pointer(PointerEventType.Press, centre)
            repeat(8) { dock.frame() }
            val pressed = dock.pixels()
            assertTrue(pressed[body].luminance() > hovered[body].luminance(), "a press did not lighten the dome: ${pressed[body]} vs ${hovered[body]}")
            assertTrue(pressed[corner].near(rest[corner]), "a press reached the node's square corner")
            assertTrue(pressed[name].near(rest[name]), "a press lit the name under the ring")
            dock.pointer(PointerEventType.Release, centre)
        } finally {
            dock.close()
        }
    }

    // Every dock has the dome, TV hardware included, and no Play/Pause plate of its own anywhere.
    @Test
    fun everyDockPlaysFromTheCentreDome() {
        listOf(false, true).forEach { tv ->
            val dock = Dock(tv = tv)
            try {
                assertNotNull(dock.dome, "no centre dome in the dock (tv=$tv)")
                assertNull(dock.plate, "a Play/Pause plate is still there (tv=$tv)")
            } finally {
                dock.close()
            }
        }
    }

    @Test
    fun aTapTogglesAndADragSkipsAndPeeksTheNeighbour() {
        val dock = Dock(tv = false)
        try {
            val centre = dock.ringCentre
            dock.tap(centre)
            assertEquals(1, dock.toggles, "a tap on the dome")
            dock.drag(centre, 48)
            assertEquals(1, dock.nexts, "a drag right")
            dock.drag(centre, -48)
            assertEquals(1, dock.previouses, "a drag left")
            assertEquals(1, dock.toggles, "a drag also toggled playback")
            // The name under the ring peeks the neighbour as the drag goes, as in the phone bar.
            assertTrue(vibe in assertNotNull(dock.dome).reads(), "sanity: the dome's name is not under it")
            dock.drag(centre, 24, release = false)
            assertTrue("Stay Asleep" in assertNotNull(dock.dome).reads(), "a drag right never peeked the next vibe")
            dock.pointer(PointerEventType.Release, centre + Offset(24f, 0f))
            assertEquals(1, dock.nexts, "a 24dp drag committed")
        } finally {
            dock.close()
        }
    }

    @Test
    fun itReadsPauseOrPlayWithTheVibeAndOffersTheSkips() {
        listOf(false to "Pause", true to "Play").forEach { (paused, word) ->
            val dock = Dock(tv = false, paused = paused)
            try {
                val dome = assertNotNull(dock.dome)
                assertEquals(listOf("$word, $vibe"), dome.config.getOrNull(SemanticsProperties.ContentDescription))
                val actions = dome.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
                assertEquals(listOf("Next vibe", "Previous vibe"), actions)
            } finally {
                dock.close()
            }
        }
    }

    // Composed in the scene's very first frame, where the launch focus request runs before layout.
    @Test
    fun theFocusRequestNeverCrashesOnFirstComposition() {
        listOf(false, true).forEach { tv ->
            val dock = Dock(tv = tv, arriveLater = false)
            try {
                repeat(4) { dock.frame() }
                assertNotNull(dock.dome, "the dock never drew its dome (tv=$tv)")
            } finally {
                dock.close()
            }
        }
    }

    // Desktop: Space and Enter toggle before any click, and the arrows still reach the window. TV:
    // the D-pad's select does the same. Its arrows move focus there, which Android does and this
    // scene cannot, so only the desktop's step routing is asserted: the eye test covers the TV's.
    @Test
    fun atLaunchTheDomeHoldsFocusSoTheKeysAndTheDpadWork() {
        listOf(false, true).forEach { tv ->
            val dock = Dock(tv = tv)
            try {
                val focused = assertNotNull(dock.focused, "nothing took focus at launch (tv=$tv)")
                assertTrue("Pause" in focused.reads(), "focus went to \"${focused.reads()}\", not the dome (tv=$tv)")
                dock.press(Key.Spacebar)
                assertEquals(1, dock.toggles, "space (tv=$tv)")
                dock.press(Key.Enter)
                assertEquals(2, dock.toggles, "enter (tv=$tv)")
                dock.press(Key.DirectionCenter)
                assertEquals(3, dock.toggles, "the D-pad's select (tv=$tv)")
                if (!tv) {
                    dock.press(Key.DirectionRight)
                    dock.press(Key.DirectionLeft)
                    assertEquals(listOf(SkipDirection.NEXT, SkipDirection.PREVIOUS), dock.steps, "the arrows never reached the window")
                    assertEquals(3, dock.toggles, "an arrow toggled playback")
                }
                // On TV the D-pad's select arrives with the ring still showing, since nothing pressed it.
                if (tv) assertTrue(dock.markPixels() > 40, "the TV dome's focus ring is not showing")
            } finally {
                dock.close()
            }
        }
    }
}

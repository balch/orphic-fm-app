package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.VizStage
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dock's centre dome rises out of the bottom bar with its name on the toggles' label line, as
 * the phone bar's does, fixed size on desktop so a third of its diameter always clears the bar (task
 * 28B). Drives the real [DjAppTvChrome], bar glass and draw order included, with the ring sized as
 * DjAppScreen sizes it.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockDomeRaiseTest*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
class DockDomeRaiseTest {
    private val vibe = "Space & Drift"
    private val background = Color(0xFF14141F)
    private val stageColor = Color(0xFF3A6E3A)

    // One ring on desktop, tablets and the Fold, whatever the window; TV hardware keeps a smaller one.
    @Test
    fun theRingIsFixedOffTelevision() {
        assertEquals(DockDomeRingSize, dockDomeRingSize(television = false))
        assertEquals(TvDockDomeRingSize, dockDomeRingSize(television = true))
    }

    /** DesktopCanvasScale's density scale for a [widthDp] x [heightDp] window. */
    private fun desktopCanvasScale(widthDp: Int, heightDp: Int) = largeScreenDensityScale(
        widthDp.toFloat(), heightDp.toFloat(), minOf(widthDp, heightDp), isTelevision = false,
        minScale = DesktopMinimumDensityScale,
    )

    /**
     * The dock as DjAppScreen builds it in a [widthDp] x [heightDp] window, a desktop's canvas scaled
     * as DesktopCanvasScale scales it, with an opaque stage that counts its taps. Rects are in the
     * scene's px, which are the window's dp; [scale] converts them to the canvas's dp.
     *
     * @param nativeDensity Stands in for a display's own pixel density (task 28B): 1.0 by default,
     *   so every existing case is unaffected. [totalScale] is the density every dp-to-px conversion
     *   here must use once this is not 1 -- [scale] alone would be missing this factor.
     * @param ringSizeOverride Task 28B's strip sweep: an explicit ring size, bypassing
     *   [dockDomeRingSize] entirely, since that function no longer has a curve to drive with a height.
     */
    private inner class Dock(
        widthDp: Int,
        heightDp: Int,
        desktop: Boolean = true,
        tv: Boolean = false,
        val name: String = vibe,
        nativeDensity: Float = 1f,
        ringSizeOverride: Dp? = null,
    ) {
        val scale = if (desktop) desktopCanvasScale(widthDp, heightDp) else 1f
        val totalScale = scale * nativeDensity
        var ringSize = 0.dp
        var toggles = 0
        var nexts = 0
        var stageTaps = 0
        var stage = Rect.Zero
        val vizStage = VizStage()
        private val scheduler = TestCoroutineScheduler()
        private var now = 1_000L

        private val pulsar: PulsarFeature = run {
            val base = PulsarViewModel.previewFeature()
            object : PulsarFeature by base {
                // Paused: the ring holds its shape, and a name too long for its lane still scrolls.
                override val stateFlow: StateFlow<PulsarUiState> =
                    MutableStateFlow(base.stateFlow.value.copy(globalPaused = true))
                override val vibeNavFlow: StateFlow<VibeNavState> =
                    MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
                override val actions: PulsarPanelActions = base.actions.copy(nextVibe = { nexts++ })
            }
        }

        val scene = ImageComposeScene(
            (widthDp * nativeDensity).toInt(), (heightDp * nativeDensity).toInt(), Density(nativeDensity), StandardTestDispatcher(scheduler),
        ) {
            OrpheusTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(totalScale),
                    LocalLiquidState provides rememberLiquidState(),
                    LocalVizStage provides vizStage,
                ) {
                    Box(Modifier.fillMaxSize().background(background)) {
                        DjAppTvChrome(
                            tvHardware = tv,
                            domeRingSize = (ringSizeOverride ?: dockDomeRingSize(television = tv)).also { ringSize = it },
                            barGlass = !tv,
                            vizHidesPanelsWhenIdle = false,
                            focusRegion = TvFocusRegionHolder(),
                            vizFeature = VizViewModel.previewFeature(),
                            pulsarFeature = pulsar,
                            timerFeature = TimerViewModel.previewFeature(),
                            onTogglePlayback = { toggles++ },
                            dockablePanels = largeScreenPanels(),
                            dockedPanels = listOf(PulsarTab, DjTab, MixTab),
                            activeSheet = null,
                            tabs = djTabs,
                            onToggleDocked = {},
                            onActiveSheetChange = {},
                            stage = {
                                // Stands in for the docked panels: opaque, and counts the taps that reach it.
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(stageColor)
                                        .onGloballyPositioned { stage = it.boundsInRoot() }
                                        .pointerInput(Unit) { detectTapGestures { stageTaps++ } },
                                )
                            },
                        )
                    }
                }
            }
        }

        init {
            repeat(3) { frame() }
        }

        fun frame(): Image {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(now * 1_000_000)
        }

        fun pixels(): PixelMap = Image.makeFromEncoded(frame().encodeToData()!!.bytes).toComposeImageBitmap().toPixelMap()

        /** A pointer event on the frame clock, so a drag's velocity is its dp per 16ms frame, not wall time. */
        fun pointer(type: PointerEventType, at: Offset, pointer: PointerType) {
            scene.sendPointerEvent(type, at, timeMillis = now, type = pointer)
            frame()
        }

        fun tap(at: Offset, type: PointerType) {
            pointer(PointerEventType.Press, at, type)
            pointer(PointerEventType.Release, at, type)
        }

        /** Drags [dx] dp right from [from] in 4dp frames, 250 dp/s: only a far drag commits. */
        fun drag(from: Offset, dx: Int, type: PointerType) {
            pointer(PointerEventType.Press, from, type)
            (4..dx step 4).forEach { pointer(PointerEventType.Move, from + Offset(it.toFloat(), 0f), type) }
            pointer(PointerEventType.Release, from + Offset(dx.toFloat(), 0f), type)
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

        private val unmerged get() = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }

        /** The dome's pointer column: the padded ring and its name, from its semantics, so unclipped. */
        val dome: Rect
            get() {
                val node = assertNotNull(
                    scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.firstOrNull { n ->
                        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Play, $name" }
                    },
                    "no dome",
                )
                return Rect(node.positionInRoot, node.size.toSize())
            }

        /** The ring alone, centred at the top of the dome under its 4dp padding, in the scene's px. */
        val ring: Rect
            get() {
                val d = dome
                val size = ringSize.value * totalScale
                val left = d.center.x - size / 2
                val top = d.top + TransportPadding.value * totalScale
                return Rect(left, top, left + size, top + size)
            }

        /** The bottom bar's top edge: the stage ends right above it. */
        val barTop: Float get() = stage.bottom

        /** The ring as laid out: from the dome's top padding to the name under it, in the scene's px. */
        val measuredRing: Float get() = text(name).positionInRoot.y - dome.top - TransportPadding.value * totalScale

        /** A toggle's clickable node, its plate's bounds, by its label. */
        fun toggle(label: String): Rect = assertNotNull(
            scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            },
            "no \"$label\" toggle",
        ).let { Rect(it.positionInRoot, it.size.toSize()) }

        fun text(label: String): SemanticsNode = assertNotNull(
            unmerged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } },
            "no \"$label\" text",
        )

        /** How wide the dome's name laid out, in the scene's px. */
        fun nameWidth(): Float {
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(text(name).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return results.single().size.width.toFloat()
        }

        /** Where [label]'s first line sits, in the scene's px. */
        fun baseline(label: String): Float {
            val node = text(label)
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return node.positionInRoot.y + results.single().firstBaseline
        }

        fun close() = scene.close()
    }

    private fun Color.near(other: Color) =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.06f

    // DJ, Mix, the dome, Horn and Timer, centred in the bar as the phone bar's tabs and dome are.
    @Test
    fun theCentreHoldsDjMixTheDomeHornAndTimer() {
        listOf(1280 to 720, 1512 to 982).forEach { (w, h) ->
            val dock = Dock(w, h)
            try {
                val centres = listOf("DJ", "Mix").map { dock.text(it).center() } + dock.dome.center.x +
                    listOf("Horn", "Timer").map { dock.text(it).center() }
                assertEquals(centres.sorted(), centres, "the centre is out of order at ${w}x$h: $centres")
                assertEquals(w / 2f, dock.dome.center.x, 0.5f, "the dome is off the bar's centre at ${w}x$h")
                // Label centres, each rounded to a whole pixel inside its item.
                assertEquals(centres[2] - centres[1], centres[3] - centres[2], 1.5f, "the dome is not midway between Mix and Horn")
            } finally {
                dock.close()
            }
        }
    }

    private fun SemanticsNode.center(): Float = positionInRoot.x + size.width / 2f

    // Mix is docked, and its wash fills its item: the name, too long for its slot, scrolls in a lane
    // that stops short of that plate, so no frame of the scroll draws into it.
    @Test
    fun theNameStaysOffADockedNeighboursPlate() {
        listOf(1280 to 720, 1512 to 982).forEach { (w, h) ->
            val dock = Dock(w, h)
            try {
                val mix = dock.toggle("Mix")
                val horn = dock.toggle("Horn")
                assertTrue(dock.nameWidth() > 100f, "sanity: the name is laid out only ${dock.nameWidth()}dp wide")
                // The name's line, and its drop below it; the ring and its paused zip sit above.
                val line = dock.text(vibe).let { Rect(it.positionInRoot, it.size.toSize()) }
                val rows = line.top.toInt() until (line.bottom + 2 * BarNameDrop.value).toInt()
                val rest = dock.pixels()
                // Past the first pass's 1.2 s delay, well into the scroll.
                repeat(110) { dock.frame().close() }
                val scrolled = dock.pixels()
                val moved = (0 until w).filter { x -> rows.any { y -> rest[x, y] != scrolled[x, y] } }
                assertTrue(moved.isNotEmpty(), "sanity: the name never scrolled at ${w}x$h")
                assertTrue(moved.min() >= mix.right + 7.5f, "the name scrolled over ${moved.min()}..${moved.max()}, into the docked Mix plate $mix at ${w}x$h")
                assertTrue(moved.max() <= horn.left, "the name scrolled over ${moved.min()}..${moved.max()}, into Horn $horn at ${w}x$h")
            } finally {
                dock.close()
            }
        }
    }

    // The dome's name sits on the toggles' label line, whatever the ring's size.
    @Test
    fun theNameSharesTheTogglesLabelLine() {
        listOf(1280 to 720, 1512 to 982).forEach { (w, h) ->
            val dock = Dock(w, h)
            try {
                assertEquals(dock.baseline("Horn"), dock.baseline(vibe), 0.5f, "the name is off the label line at ${w}x$h")
            } finally {
                dock.close()
            }
        }
    }

    // TV's smaller ring and the dock's ring report the same bar height (the bar's layout ignores the
    // ring's size), and the dock ring still clears the bar by its third.
    @Test
    fun theRingSizeNeverChangesTheBarsHeight() {
        val floor = Dock(1280, 720, desktop = false, ringSizeOverride = TvDockDomeRingSize)
        val desktop = Dock(1280, 720)
        try {
            assertEquals(TvDockDomeRingSize, floor.ringSize)
            assertEquals(DockDomeRingSize, desktop.ringSize)
            assertEquals(TvDockDomeRingSize.value, floor.measuredRing, 0.5f, "the floor ring")
            assertEquals(DockDomeRingSize.value, desktop.measuredRing, 0.5f, "the desktop ring")
            assertEquals(720 - floor.barTop, 720 - desktop.barTop, 0.5f, "the ring size changed the bar's height")
            assertTrue(floor.barTop - floor.ring.top >= 2f, "the floor ring leaves the bar: ${floor.ring} above ${floor.barTop}")
            assertTrue(
                desktop.barTop - desktop.ring.top >= DockDomeRingSize.value / 3 - 0.5f,
                "the desktop ring rises only ${desktop.barTop - desktop.ring.top}dp out of the bar",
            )
        } finally {
            floor.close()
            desktop.close()
        }
    }

    /**
     * The rule itself: off TV hardware, [DockDomeRiseFraction] of the ring's diameter rises above the
     * bar's visible top edge, on desktop windows (1000x700 on a scaled canvas) and tablets alike, at
     * native densities 1.0 and 2.0.
     */
    @Test
    fun theRingRisesAThirdOfItsDiameterAtEveryWindowAndDensity() {
        val windows = listOf(1280 to 720, 1512 to 982, 1920 to 1080, 1000 to 700).map { Triple(it.first, it.second, true) } +
            listOf(1032 to 1376, 1366 to 1024).map { Triple(it.first, it.second, false) }
        windows.forEach { (w, h, desktop) ->
            listOf(1f, 2f).forEach { density ->
                val dock = Dock(w, h, desktop = desktop, nativeDensity = density)
                try {
                    assertEquals(DockDomeRingSize, dock.ringSize, "ring at ${w}x$h @${density}x")
                    val rise = (dock.barTop - dock.ring.top) / dock.totalScale
                    val ringSizeDp = dock.ringSize.value
                    // 2 real device px, converted to canvas-dp the same way rise is (by totalScale):
                    // independent dp-constant rounding compounds more on a scaled canvas.
                    val toleranceDp = 2f / dock.totalScale / ringSizeDp
                    assertEquals(DockDomeRiseFraction, rise / ringSizeDp, toleranceDp, "${w}x$h @${density}x rise fraction")
                } finally {
                    dock.close()
                }
            }
        }
    }

    // Neither the stage, drawn before the bar, nor the bar's clipping glass hides the raised part.
    @Test
    fun theRaisedDomeDrawsOverTheStage() {
        val dock = Dock(1512, 982)
        try {
            val ring = dock.ring
            val px = dock.pixels()
            val top = ring.top.toInt() + 2
            val bottom = dock.barTop.toInt() - 1
            var drawn = 0
            for (y in top until bottom) for (x in ring.left.toInt() until ring.right.toInt()) {
                if (!px[x, y].near(stageColor)) drawn++
            }
            val area = (bottom - top) * ring.width.toInt()
            assertTrue(drawn > area / 2, "only $drawn of the raised part's $area px are drawn over the stage")
        } finally {
            dock.close()
        }
    }

    @Test
    fun aTapOnTheRaisedPartTogglesAndADragFromItSkips() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val dock = Dock(1512, 982)
            try {
                // Midway up the raised part: proportional to the rise, not a fixed offset, so this
                // still lands comfortably on the ring however much of it clears the bar (task 28B
                // shrank the desktop ring's rise from 62dp to 41dp).
                val rise = dock.barTop - dock.ring.top
                val raised = Offset(dock.ring.center.x, dock.barTop - rise / 2)
                assertTrue(raised.y > dock.ring.top + rise / 4, "sanity: $raised is not on the raised ring ${dock.ring}")
                dock.tap(raised, type)
                assertEquals(1, dock.toggles, "$type: a tap on the raised part")
                // Past the 32dp commit after the scene's 18dp touch slop, which a drag spends before it counts.
                dock.drag(raised, 64, type)
                assertEquals(1, dock.nexts, "$type: a drag right from the raised part")
                assertEquals(1, dock.toggles, "$type: the drag also toggled playback")
                assertEquals(0, dock.stageTaps, "$type: the dome's input reached the stage")
            } finally {
                dock.close()
            }
        }
    }

    // Only the dome's own bounds take input over the stage; the bar's empty width never does.
    @Test
    fun aTapOnTheStageBesideTheRaisedDomeReachesTheStage() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val dock = Dock(1512, 982)
            try {
                val ring = dock.ring
                dock.tap(Offset(dock.dome.right + 8f, dock.barTop - 24f), type)
                dock.tap(Offset(ring.center.x, ring.top - 8f), type)
                dock.tap(Offset(ring.right + 400f, dock.barTop - 4f), type)
                assertEquals(0, dock.toggles, "$type: a tap beside the dome toggled playback")
                assertEquals(3, dock.stageTaps, "$type: a tap beside the dome never reached the stage")
            } finally {
                dock.close()
            }
        }
    }

    // Mid-size rings in their wider slot, under real names wider than the ring: the slot's strips
    // beside the raised ring belong to the stage, and only the ring and its name toggle. From 96dp
    // (widest strips) to 141dp (strips nearly closed); 123dp is task 28B's fixed desktop size.
    @Test
    fun besideAMidSizeRaisedRingTheSlotsStripsReachTheStage() {
        listOf(vibe, "Stay Asleep").forEach { name ->
            listOf(700 to 96, 780 to 109, 824 to 118, 860 to 123, 878 to 125, 895 to 133, 936 to 141).forEach { (height, size) ->
                listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
                    val dock = Dock(1400, height, name = name, ringSizeOverride = size.dp)
                    val what = "$type, \"$name\" under the ${size}dp ring"
                    try {
                        assertEquals(1f, dock.scale, "sanity: a 1400x$height window is not scaled")
                        assertEquals(size.dp, dock.ringSize, "sanity: the ring at 1400x$height")
                        val ring = dock.ring
                        assertTrue(dock.nameWidth() > ring.width + 12f, "sanity: $what, the name is only ${dock.nameWidth()}dp wide")
                        assertEquals(ring.width, dock.dome.width, 0.5f, "$what: the name widened the tap target past the ring")
                        val rise = dock.barTop - ring.top
                        assertTrue(rise >= 2f, "sanity: the ${size}dp ring rises only ${rise}dp over the stage")
                        // Up the raised part, over the stage; beside it, in the slot's strip where it is wider than 6dp.
                        val y = dock.barTop - minOf(16f, rise / 2)
                        dock.tap(Offset(ring.left - 6f, y), type)
                        dock.tap(Offset(ring.right + 6f, y), type)
                        assertEquals(0, dock.toggles, "$what: a tap beside the raised ring toggled playback")
                        assertEquals(2, dock.stageTaps, "$what: a tap beside the raised ring never reached the stage")
                        dock.tap(Offset(ring.center.x, y), type)
                        assertEquals(1, dock.toggles, "$what: a tap on the raised ring")
                        // The name, in the bar below its top edge.
                        val line = dock.text(name).let { Rect(it.positionInRoot, it.size.toSize()) }
                        assertTrue(line.center.y > dock.barTop, "sanity: $what, the name's line $line is not in the bar")
                        dock.tap(Offset(ring.center.x, line.center.y), type)
                        assertEquals(2, dock.toggles, "$what: a tap on the name")
                        assertEquals(2, dock.stageTaps, "$what: the ring's or the name's tap also reached the stage")
                    } finally {
                        dock.close()
                    }
                }
            }
        }
    }

    // DesktopCanvasScale lays a 1000x700 window out on a 1280x896 canvas; the ring is now fixed
    // regardless, so it is still DockDomeRingSize, laid out in the canvas's (scaled) dp -- not the
    // floor a window-height curve would once have picked for a 700dp-tall window.
    @Test
    fun aNarrowDesktopWindowOnAScaledCanvasKeepsAFixedRing() {
        val dock = Dock(1000, 700)
        try {
            assertEquals(0.78125f, dock.scale, "sanity: DesktopCanvasScale scales this window")
            assertEquals(DockDomeRingSize, dock.ringSize, "the ring follows desktop/tv alone now")
            assertEquals(DockDomeRingSize.value * dock.scale, dock.measuredRing, 0.5f, "the ring, in canvas dp")
        } finally {
            dock.close()
        }
    }

    // Television hardware gets its smaller dome; a tablet gets the desktop's.
    @Test
    fun televisionGetsTheSmallerDomeAndTabletsTheDesktops() {
        listOf(Dock(1280, 720, desktop = false, tv = true) to TvDockDomeRingSize, Dock(1366, 1024, desktop = false) to DockDomeRingSize)
            .forEach { (dock, ring) ->
                try {
                    assertEquals(ring, dock.ringSize)
                    assertEquals(ring.value, dock.measuredRing, 0.5f, "the ring")
                } finally {
                    dock.close()
                }
            }
    }

    // Off TV the dome's slot has DockDomeSideRoom more on each side, so Mix and Horn sit 12dp from it.
    @Test
    fun theDomeHasExtraRoomBesideItOffTelevision() {
        listOf(Dock(1512, 982) to DockDomeSideRoom, Dock(1366, 1024, desktop = false) to DockDomeSideRoom, Dock(1280, 720, desktop = false, tv = true) to 0.dp)
            .forEach { (dock, side) ->
                try {
                    val between = (dock.toggle("Horn").left - dock.toggle("Mix").right) / dock.totalScale
                    val expected = dockDomeSlot(dock.ringSize) + (TvBottomBarItemGap + side) * 2
                    assertEquals(expected.value, between, 1f, "Mix to Horn around a ${dock.ringSize} dome")
                } finally {
                    dock.close()
                }
            }
    }
}

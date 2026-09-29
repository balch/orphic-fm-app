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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dock's centre dome hangs [DockDomeDrop] below the toggles' label line with its name in a pill
 * above it over the stage, on TV hardware too with a smaller ring. Drives the real [DjAppTvChrome],
 * bar glass and draw order included, with the ring sized as DjAppScreen sizes it.
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

        /** The dome's pointer column, the padded ring alone (its pill is outside), from its semantics, so unclipped. */
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

        /** The ring alone, centred across the dome at its bottom inside its 4dp padding, under the pill, in the scene's px. */
        val ring: Rect
            get() {
                val d = dome
                val size = ringSize.value * totalScale
                val left = d.center.x - size / 2
                val top = d.bottom - TransportPadding.value * totalScale - size
                return Rect(left, top, left + size, top + size)
            }

        /** The bottom bar's top edge: the stage ends right above it. */
        val barTop: Float get() = stage.bottom

        /** The name's line, in the scene's px. */
        val nameLine: Rect get() = text(name).let { Rect(it.positionInRoot, it.size.toSize()) }

        /** The pill's height around the name: the line and its padding above and below. */
        val pill: Rect
            get() = nameLine.let {
                val y = VibeNamePillPaddingY.value * totalScale
                Rect(it.left, it.top - y, it.right, it.bottom + y)
            }

        /** The ring as laid out, in the scene's px: the dome's target, the padded ring alone, less its padding. */
        val measuredRing: Float
            // Each padding in whole px, as layout rounds it.
            get() = dome.height - 2 * px(TransportPadding)

        private fun px(dp: Dp): Float = (dp.value * totalScale).roundToInt().toFloat()

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

        private fun nameLayout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(text(name).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return results.single()
        }

        /** How wide the dome's name laid out, in the scene's px. */
        fun nameWidth(): Float = nameLayout().size.width.toFloat()

        /** Whether the name was cut short: ellipsized, or laid out wider than it was given. */
        fun nameCut(): Boolean = nameLayout().let { it.isLineEllipsized(0) || it.size.width > it.layoutInput.constraints.maxWidth }

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

    // DJ, Mix, the dome, Pulsar and Horn, centred in the bar as the phone bar's tabs and dome are.
    @Test
    fun theCentreHoldsDjMixTheDomePulsarAndHorn() {
        listOf(1280 to 720, 1512 to 982).forEach { (w, h) ->
            val dock = Dock(w, h)
            try {
                val centres = listOf("DJ", "Mix").map { dock.text(it).center() } + dock.dome.center.x +
                    listOf("Pulsar", "Horn").map { dock.text(it).center() }
                assertEquals(centres.sorted(), centres, "the centre is out of order at ${w}x$h: $centres")
                assertEquals(w / 2f, dock.dome.center.x, 0.5f, "the dome is off the bar's centre at ${w}x$h")
                // Label centres, each rounded to a whole pixel inside its item.
                assertEquals(centres[2] - centres[1], centres[3] - centres[2], 1.5f, "the dome is not midway between Mix and Pulsar")
            } finally {
                dock.close()
            }
        }
    }

    private fun SemanticsNode.center(): Float = positionInRoot.x + size.width / 2f

    // The name rides above the bar, in a pill wider than the ring, whole and centred on it, TV included.
    @Test
    fun theNameRidesAboveTheBarWholeInItsPill() {
        listOf(Triple(1280, 720, false), Triple(1512, 982, false), Triple(960, 540, true)).forEach { (w, h, tv) ->
            val dock = Dock(w, h, desktop = !tv, tv = tv)
            try {
                val line = dock.nameLine
                assertTrue(dock.pill.bottom < dock.barTop, "the pill $line reaches into the bar at ${dock.barTop} at ${w}x$h")
                assertTrue(dock.pill.bottom < dock.ring.top, "the pill $line overlaps the ring ${dock.ring} at ${w}x$h")
                assertTrue(dock.nameWidth() > dock.ring.width, "sanity: the name is only ${dock.nameWidth()}px wide at ${w}x$h")
                assertTrue(!dock.nameCut(), "the name was cut short in its pill at ${w}x$h")
                assertEquals(dock.dome.center.x, line.center.x, 1f, "the name is off the dome's centre at ${w}x$h")
            } finally {
                dock.close()
            }
        }
    }

    // TV hardware's smaller ring hangs the same drop below the label line, and still clears the bar.
    @Test
    fun onTelevisionTheRingHangsTheSameDrop() {
        val dock = Dock(960, 540, desktop = false, tv = true)
        try {
            assertEquals(DockDomeDrop.value, dock.ring.bottom - dock.baseline("Horn"), 1f, "the TV ring's drop below the label line")
            assertTrue(dock.barTop - dock.ring.top >= 2f, "the TV ring never rises out of the bar: ${dock.ring} under ${dock.barTop}")
        } finally {
            dock.close()
        }
    }

    // TV's smaller ring and the dock's ring report the same bar height (the bar's layout ignores the
    // ring's size), and the dock ring still rises out of the bar.
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
            assertTrue(
                desktop.barTop - desktop.ring.top >= 16f,
                "the desktop ring rises only ${desktop.barTop - desktop.ring.top}dp out of the bar",
            )
        } finally {
            floor.close()
            desktop.close()
        }
    }

    /**
     * The rule itself: off TV hardware, the ring's bottom sits [DockDomeDrop] below the toggles'
     * label line, on desktop windows (1000x700 on a scaled canvas) and tablets alike, at native
     * densities 1.0 and 2.0.
     */
    @Test
    fun theRingHangsItsDropBelowTheLabelLineAtEveryWindowAndDensity() {
        val windows = listOf(1280 to 720, 1512 to 982, 1920 to 1080, 1000 to 700).map { Triple(it.first, it.second, true) } +
            listOf(1032 to 1376, 1366 to 1024).map { Triple(it.first, it.second, false) }
        windows.forEach { (w, h, desktop) ->
            listOf(1f, 2f).forEach { density ->
                val dock = Dock(w, h, desktop = desktop, nativeDensity = density)
                try {
                    assertEquals(DockDomeRingSize, dock.ringSize, "ring at ${w}x$h @${density}x")
                    val drop = (dock.ring.bottom - dock.baseline("Horn")) / dock.totalScale
                    // 2 real device px, converted to canvas-dp the same way drop is (by totalScale):
                    // independent dp-constant rounding compounds more on a scaled canvas.
                    val toleranceDp = 2f / dock.totalScale
                    assertEquals(DockDomeDrop.value, drop, toleranceDp, "${w}x$h @${density}x drop below the label line")
                } finally {
                    dock.close()
                }
            }
        }
    }

    // Neither the stage, drawn before the bar, nor the bar's clipping glass hides the raised part,
    // and the pill's glass draws over the stage beside the name.
    @Test
    fun theRaisedDomeAndItsPillDrawOverTheStage() {
        val dock = Dock(1512, 982)
        try {
            val ring = dock.ring
            val px = dock.pixels()
            val top = ring.top.toInt() + 2
            val bottom = dock.barTop.toInt() - 1
            // Only the cap of the ring's circle rises, so only its pixels count.
            val radius = ring.width / 2
            var drawn = 0
            var area = 0
            for (y in top until bottom) for (x in ring.left.toInt() until ring.right.toInt()) {
                if (hypot(x + 0.5f - ring.center.x, y + 0.5f - ring.center.y) > radius) continue
                area++
                if (!px[x, y].near(stageColor)) drawn++
            }
            assertTrue(area > 0, "sanity: the ring never rose over the stage")
            assertTrue(drawn > area / 2, "only $drawn of the raised part's $area px are drawn over the stage")
            // In the pill's padding, just left of the name's line.
            val inPill = Offset(dock.nameLine.left - 4f * dock.totalScale, dock.pill.center.y)
            assertTrue(!px[inPill.x.toInt(), inPill.y.toInt()].near(stageColor), "the pill left the stage bare at $inPill")
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

    // The pill is only a label: a tap or a drag on it reaches the stage under it, never the dome.
    @Test
    fun aTapOrADragOnThePillReachesTheStage() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val dock = Dock(1512, 982)
            try {
                val onName = dock.nameLine.center
                assertTrue(onName.y < dock.dome.top, "sanity: the name $onName is inside the dome's target ${dock.dome}")
                dock.tap(onName, type)
                assertEquals(1, dock.stageTaps, "$type: a tap on the pill never reached the stage")
                dock.drag(onName, 64, type)
                assertEquals(0, dock.toggles, "$type: the pill toggled playback")
                assertEquals(0, dock.nexts, "$type: a drag on the pill skipped")
            } finally {
                dock.close()
            }
        }
    }

    // Only the dome's own bounds take input over the stage; the bar's empty width never does, nor
    // does the pill, wider than the ring or not.
    @Test
    fun aTapOnTheStageBesideTheRaisedDomeReachesTheStage() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val dock = Dock(1512, 982)
            try {
                val ring = dock.ring
                dock.tap(Offset(dock.dome.right + 8f, dock.barTop - 24f), type)
                dock.tap(Offset(ring.center.x, dock.pill.top - 8f), type)
                dock.tap(Offset(ring.right + 400f, dock.barTop - 4f), type)
                assertTrue(dock.pill.left < dock.dome.left - 8f, "sanity: the pill ${dock.pill} is no wider than the dome ${dock.dome}")
                dock.tap(Offset(dock.dome.left - 4f, dock.pill.center.y), type)
                assertEquals(0, dock.toggles, "$type: a tap beside the dome toggled playback")
                assertEquals(4, dock.stageTaps, "$type: a tap beside the dome never reached the stage")
            } finally {
                dock.close()
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

    // Off TV the dome's slot has DockDomeSideRoom more on each side, so Mix and Pulsar sit 12dp from it.
    @Test
    fun theDomeHasExtraRoomBesideItOffTelevision() {
        listOf(Dock(1512, 982) to DockDomeSideRoom, Dock(1366, 1024, desktop = false) to DockDomeSideRoom, Dock(1280, 720, desktop = false, tv = true) to 0.dp)
            .forEach { (dock, side) ->
                try {
                    val between = (dock.toggle("Pulsar").left - dock.toggle("Mix").right) / dock.totalScale
                    val expected = dock.ringSize + (TvBottomBarItemGap + side) * 2
                    assertEquals(expected.value, between, 1f, "Mix to Pulsar around a ${dock.ringSize} dome")
                } finally {
                    dock.close()
                }
            }
    }
}

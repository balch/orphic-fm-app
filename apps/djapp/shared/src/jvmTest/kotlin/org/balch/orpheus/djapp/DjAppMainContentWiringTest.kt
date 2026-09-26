package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.PanelFadeOutMs
import org.balch.orpheus.ui.viz.PanelIdleFade
import org.balch.orpheus.ui.viz.PanelIdleFadeWatcher
import org.balch.orpheus.ui.viz.PanelIdleTimeoutMs
import org.balch.orpheus.ui.viz.PanelWakeOverlay
import org.balch.orpheus.ui.viz.VizStage
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stage report and the idle fade are per-branch modifiers on [DjAppMainContent], so a test
 * built on a stand-in layout would pass with `.vizStage(...)` or `.panelIdleFade(...)` deleted
 * from any one branch. This drives the real composable, once per [DjLayout], inside the same
 * chrome `DjAppScreen` wraps it in.
 *
 * Two properties per layout, both read off the rendered frame rather than off the source:
 *  - the reported stage leaves the header and the navigation out (tabletop's header sits inside
 *    it by construction and is reported separately as the live chrome band);
 *  - with the fade at 0 nothing inside the stage is painted, while the chrome still is (the phone
 *    bar's ring rises over the stage's bottom edge, so its bounds count as chrome).
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DjAppMainContentWiringTest*' --rerun
 */
class DjAppMainContentWiringTest {

    @Test
    fun `every layout reports a stage clear of its chrome and fades only what is inside it`() {
        Case.All.forEach { case ->
            val shot = render(case)

            val stage = assertNotNull(
                shot.stage,
                "${case.name}: no stage was reported, so the picture would take the whole window",
            )
            assertTrue(
                stage.width > 1f && stage.height > 1f,
                "${case.name}: the reported stage is degenerate: $stage",
            )
            assertTrue(
                shot.content.contains(stage, slack = 1f),
                "${case.name}: the stage $stage escapes the content area ${shot.content}",
            )

            // Nothing inside the stage may be painted: everything there wears the fade. The
            // tabletop header and the phone bar's raised dome are the chrome inside it, and keep drawing.
            val faded = stage.deflate(EdgeSlack)
            val painted = shot.paintedIn(faded, except = case.chromeInStage(shot))
            assertTrue(
                painted == 0,
                "${case.name}: $painted pixels inside the stage survived the fade, so a panel " +
                    "there is not wearing panelIdleFade. Stage $stage, painted within " +
                    shot.paintedBox(faded, case.chromeInStage(shot)),
            )

            // The navigation is outside the content area in every layout that has one, and the
            // television bars are outside it on the large screen.
            assertTrue(
                shot.paintedOutside(shot.content) > 0,
                "${case.name}: the navigation painted nothing, so the case proves nothing",
            )

            case.assertHeader(shot, stage)
        }
    }

    // ==================== the dock never fades ====================

    /**
     * The dock's own bottom bar is what shows and hides panels there, so an idle fade would both
     * argue with those buttons and hide a panel the user parked on screen deliberately.
     */
    @Test
    fun `the desktop dock never fades, whatever the visualization asks for`() {
        val d = FadeScene(1280, 720)
        d.run(10_000)

        assertEquals(DjLayout.LargeScreen, d.layout, "sanity: this window size is the dock")
        assertTrue(d.alpha == 1f, "the dock faded: ${d.alpha}")
        d.clickPanel()
        assertEquals(1, d.clicks, "a click in the dock was eaten by a wake overlay")
        d.close()
    }

    @Test
    fun `portrait and landscape still fade`() {
        listOf("portrait" to (360 to 780), "landscape" to (780 to 360)).forEach { (name, size) ->
            val d = FadeScene(size.first, size.second)
            d.run(PanelIdleTimeoutMs + PanelFadeOutMs + 4 * FrameMs)
            assertTrue(d.alpha < 0.01f, "$name stopped fading: ${d.alpha}")
            d.clickPanel()
            assertEquals(0, d.clicks, "$name let the waking click through")
            d.close()
        }
    }

    /**
     * Resizing a desktop window across the dock threshold with the panels already gone. The gate
     * is read inside the layout box, so the frame that picks the dock is the frame that stops
     * blocking input; the watcher snaps alpha back in the same pass.
     */
    @Test
    fun `growing into the dock while the panels are gone brings them straight back`() {
        val d = FadeScene(360, 780)
        d.run(PanelIdleTimeoutMs + PanelFadeOutMs + 4 * FrameMs)
        assertTrue(d.alpha < 0.01f, "the panels never went: ${d.alpha}")
        d.clickPanel()
        assertEquals(0, d.clicks, "sanity: a click while hidden should be eaten")

        d.resize(1280, 720)
        assertEquals(DjLayout.LargeScreen, d.layout, "the resize did not reach the dock")
        assertEquals(1f, d.alpha, "the dock did not bring the panels straight back: ${d.alpha}")
        d.clickPanel()
        assertEquals(1, d.clicks, "the wake overlay outlived the layout it belonged to")

        // And back out again: the fade is a property of the layout, not a one-way switch.
        d.resize(360, 780)
        d.run(PanelIdleTimeoutMs + PanelFadeOutMs + 4 * FrameMs)
        assertTrue(d.alpha < 0.01f, "leaving the dock did not restore the fade: ${d.alpha}")
        d.close()
    }

    @Test
    fun `a docked panel follows the bottom bar's toggle, never the clock`() {
        val d = FadeScene(1280, 720)
        d.run(10_000)
        assertTrue(d.panelIsPainted(), "ten idle seconds took a docked panel off the screen")

        d.dock(emptyList())
        assertTrue(!d.panelIsPainted(), "undocking every panel left one painted")

        d.dock(listOf(PulsarTab))
        assertTrue(d.panelIsPainted(), "docking a panel again did not bring it back")
        d.run(10_000)
        assertTrue(d.panelIsPainted(), "the panel went away on its own after being docked")
        d.close()
    }

    /**
     * The slice of `DjAppScreen` that owns the fade: the root activity observer, the layout box,
     * the gate, the watcher and the wake overlay, around the real `DjAppMainContent`. Time is
     * virtual on both clocks, the way `PanelIdleFadeSceneTest` drives its own scene.
     */
    private class FadeScene(width: Int, height: Int) {
        var nowMs = 0L
        var clicks = 0
        var layout: DjLayout? = null
        private var panel = Rect.Zero
        private val scheduler = TestCoroutineScheduler()
        private val fade = PanelIdleFade { nowMs }
        private val stage = VizStage()
        private val docked = mutableStateOf(listOf<DjRoute>(PulsarTab))
        private val host = DjAppMainContentWiringTest()
        private val scene: ImageComposeScene

        init {
            scene = ImageComposeScene(
                width = width,
                height = height,
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
                            DjLayoutBox(Modifier.fillMaxSize()) { resolved ->
                                layout = resolved
                                val enabled =
                                    fadesPanelsWhenIdle(
                                        resolved,
                                        vizOptsIn = true,
                                        sheetOpen = false,
                                        turntableUp = false,
                                    )
                                PanelIdleFadeWatcher(fade, enabled)
                                host.Chrome(resolved) {
                                    host.MainContent(
                                        layout = resolved,
                                        dockedPanels = docked.value,
                                        onPanelClick = { clicks++ },
                                        onPanelBounds = { panel = it },
                                    )
                                }
                                PanelWakeOverlay(fade, stage, enabled)
                            }
                        }
                    }
                }
            }
            // DjLayoutBox decides from the previous frame's size, so the first render measures
            // and the second is the first one with a layout at all.
            repeat(2) { step(0) }
        }

        val alpha: Float get() = fade.alpha.value

        fun step(ms: Long) {
            nowMs += ms
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
            scene.render(nowMs * 1_000_000L)
        }

        fun run(ms: Long) = repeat((ms / FrameMs).toInt()) { step(FrameMs) }

        fun resize(width: Int, height: Int) {
            scene.constraints = Constraints.fixed(width, height)
            // One render to measure the new size, one to compose the layout it resolves to, one
            // for the watcher's snap to reach the picture.
            repeat(3) { step(FrameMs) }
        }

        fun dock(panels: List<DjRoute>) {
            docked.value = panels
            panel = Rect.Zero
            repeat(2) { step(FrameMs) }
        }

        fun clickPanel() {
            val at = panel.center
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            step(FrameMs)
        }

        /** Whether a docked panel is actually on screen, read off the frame rather than the list. */
        fun panelIsPainted(): Boolean {
            if (panel.isEmpty) return false
            val px = Image.makeFromEncoded(scene.render(nowMs * 1_000_000L).encodeToData()!!.bytes)
                .toComposeImageBitmap()
                .toPixelMap()
            val x = panel.center.x.toInt().coerceIn(0, px.width - 1)
            val y = panel.center.y.toInt().coerceIn(0, px.height - 1)
            return px[x, y] != VoidColour
        }

        fun close() = scene.close()
    }

    /** One rendered frame plus what the layout reported while producing it. */
    private class Shot(
        val stage: Rect?,
        val chromeBand: Rect?,
        /** The vibe transport's bounds, ring and name: the phone bar's ring rises into the stage. */
        val transport: Rect?,
        val content: Rect,
        val width: Int,
        val height: Int,
        private val painted: (Int, Int) -> Boolean,
    ) {
        fun paintedIn(rect: Rect, except: List<Rect> = emptyList()): Int {
            var count = 0
            forEachPixel(rect) { x, y ->
                if (except.any { it.holds(x, y) }) return@forEachPixel
                if (painted(x, y)) count++
            }
            return count
        }

        /** Where the survivors are, so a failure says which edge or panel leaked. */
        fun paintedBox(rect: Rect, except: List<Rect>): String {
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
            forEachPixel(rect) { x, y ->
                if (except.any { it.holds(x, y) }) return@forEachPixel
                if (painted(x, y)) {
                    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                }
            }
            return "[$l,$t..$r,$b]"
        }

        /** Painted pixels in the window but outside [rect], which is where the chrome lives. */
        fun paintedOutside(rect: Rect): Int {
            var count = 0
            forEachPixel(Rect(0f, 0f, width.toFloat(), height.toFloat())) { x, y ->
                if (rect.deflate(-EdgeSlack).holds(x, y)) return@forEachPixel
                if (painted(x, y)) count++
            }
            return count
        }

        private inline fun forEachPixel(rect: Rect, body: (Int, Int) -> Unit) {
            val left = rect.left.toInt().coerceIn(0, width - 1)
            val right = rect.right.toInt().coerceIn(0, width - 1)
            val top = rect.top.toInt().coerceIn(0, height - 1)
            val bottom = rect.bottom.toInt().coerceIn(0, height - 1)
            for (y in top..bottom) for (x in left..right) body(x, y)
        }
    }

    private class Case(
        val name: String,
        val layout: DjLayout,
        val width: Int,
        val height: Int,
        /** Whether a header of this layout's own sits between the content top and the stage. */
        val headerAboveStage: Boolean = true,
    ) {
        /** Tabletop reports the band its header occupies, and the phone bar's ring rises over the stage's bottom edge. */
        fun chromeInStage(shot: Shot): List<Rect> = listOfNotNull(
            if (layout is DjLayout.Tabletop) shot.chromeBand?.deflate(-EdgeSlack) else null,
            if (!layout.usesLandscapeChrome()) shot.transport?.deflate(-EdgeSlack) else null,
        )

        fun assertHeader(shot: Shot, stage: Rect) {
            if (layout is DjLayout.Tabletop) {
                val band = assertNotNull(
                    shot.chromeBand,
                    "$name: the header between the folded halves was never reported, so the " +
                        "wake overlay would cover it",
                )
                assertTrue(
                    stage.contains(band, slack = 1f),
                    "$name: the chrome band $band is not inside the stage $stage",
                )
                assertTrue(
                    shot.paintedIn(band.deflate(EdgeSlack)) > 0,
                    "$name: the header band painted nothing, so the case proves nothing",
                )
                return
            }
            if (!headerAboveStage) return
            val above = Rect(shot.content.left, shot.content.top, shot.content.right, stage.top)
            assertTrue(
                above.height > 1f,
                "$name: the stage starts at the top of the content, so it includes the header",
            )
            assertTrue(
                shot.paintedIn(above.deflate(EdgeSlack)) > 0,
                "$name: nothing is painted above the stage, so the header is not really there",
            )
        }

        companion object {
            val All = listOf(
                Case("portrait", DjLayout.Portrait, 360, 780),
                Case("portrait pair", DjLayout.PortraitPair, 780, 900),
                Case("landscape", DjLayout.Landscape, 780, 360),
                // The dock IS the band between the two television bars, and has no header.
                Case("large screen", DjLayout.LargeScreen, 1280, 720, headerAboveStage = false),
                Case(
                    "tabletop",
                    DjLayout.Tabletop(Hinge(topPx = 440, bottomPx = 470), pair = false),
                    800,
                    900,
                ),
            )
        }
    }

    private fun render(case: Case): Shot {
        val stage = VizStage()
        val fade = PanelIdleFade { 0L }
        // Straight to gone, without the watcher: this test is about what the fade reaches, not
        // about its timing, which PanelIdleFadeTest already drives on a virtual clock.
        runBlocking { fade.alpha.snapTo(0f) }
        var content = Rect.Zero

        val scene = ImageComposeScene(case.width, case.height, Density(1f)) {
            OrpheusTheme {
                CompositionLocalProvider(
                    LocalVizStage provides stage,
                    LocalPanelIdleFade provides fade,
                ) {
                    Box(Modifier.fillMaxSize().background(VoidColour)) {
                        Chrome(case.layout) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { content = it.boundsInRoot() },
                            ) {
                                MainContent(case.layout)
                            }
                        }
                    }
                }
            }
        }
        return try {
            // Twice: DjTabletopLayout sizes its halves from the offset the first pass measured.
            scene.render()
            val px = Image.makeFromEncoded(scene.render().encodeToData()!!.bytes)
                .toComposeImageBitmap()
                .toPixelMap()
            Shot(
                stage = stage.bounds,
                chromeBand = stage.chromeBand,
                transport = scene.semanticsOwners.firstNotNullOfOrNull { it.rootSemanticsNode.findTransport() }?.boundsInRoot,
                content = content,
                width = case.width,
                height = case.height,
                painted = { x, y -> px[x, y] != VoidColour },
            )
        } finally {
            scene.close()
        }
    }

    /** The navigation or the television bars, exactly as `DjAppScreen` puts them around the stage. */
    @Composable
    private fun Chrome(layout: DjLayout, content: @Composable () -> Unit) {
        when (layout) {
            DjLayout.LargeScreen -> DjAppTvChrome(
                tvHardware = false,
                domeRingSize = BarRingSize,
                barGlass = false,
                vizHidesPanelsWhenIdle = true,
                focusRegion = remember { TvFocusRegionHolder() },
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
                timerFeature = TimerViewModel.previewFeature(),
                onTogglePlayback = {},
                dockablePanels = largeScreenPanels(),
                dockedPanels = emptyList(),
                activeSheet = null,
                tabs = djTabs,
                onToggleDocked = {},
                onActiveSheetChange = {},
                stage = content,
            )
            else -> DjAppNavScaffold(
                isSelected = { it == DjTab },
                onItemClick = {},
                layout = layout,
                pulsarFeature = PulsarViewModel.previewFeature(),
                timerFeature = TimerViewModel.previewFeature(),
                onTogglePlayback = {},
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }

    @Composable
    private fun MainContent(
        layout: DjLayout,
        dockedPanels: List<DjRoute> = listOf(PulsarTab, DjTab),
        onPanelClick: () -> Unit = {},
        onPanelBounds: (Rect) -> Unit = {},
    ) {
        DjAppMainContent(
            layout = layout,
            dockedPanels = dockedPanels,
            pairPanels = listOf(DjTab, TimerTab),
            pulsarFeature = PulsarViewModel.previewFeature(),
            synthEngine = RenderProbeSynthEngine(),
            vizFeature = VizViewModel.previewFeature(),
            onShowVibeInfo = {},
            // Stand-ins for the real panels: this test is about which region they land in,
            // whether the fade reaches them and whether a click gets through, not about what any
            // one of them draws.
            routePanel = { _, mod, _ ->
                Box(
                    mod
                        .fillMaxSize()
                        .onGloballyPositioned { onPanelBounds(it.boundsInRoot()) }
                        .background(PanelColour)
                        .clickable { onPanelClick() },
                )
            },
            navContent = { mod ->
                Box(
                    mod
                        .fillMaxSize()
                        .onGloballyPositioned { onPanelBounds(it.boundsInRoot()) }
                        .background(PanelColour)
                        .clickable { onPanelClick() },
                )
            },
        )
    }

    private companion object {
        val VoidColour = Color(0xFF14141F)
        val PanelColour = Color(0xFF3FC08A)

        /**
         * How far a rect is pulled in before its pixels are read. The header's raised plate
         * drops a shadow four pixels past its own bounds, and antialiasing adds a little more;
         * a panel that failed to fade would paint the whole region, not its first few rows.
         */
        const val EdgeSlack = 6f

        /** One frame at 60Hz, the step the virtual clocks advance by. */
        const val FrameMs = 16L

        fun Rect.holds(x: Int, y: Int): Boolean =
            x >= left && x < right && y >= top && y < bottom

        /** The vibe transport's node: the one carrying the "Next vibe" action. */
        fun SemanticsNode.findTransport(): SemanticsNode? {
            val actions = config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            if (actions.any { it.label == "Next vibe" }) return this
            return children.firstNotNullOfOrNull { it.findTransport() }
        }

        fun Rect.contains(other: Rect, slack: Float): Boolean =
            other.left >= left - slack && other.right <= right + slack &&
                other.top >= top - slack && other.bottom <= bottom + slack
    }
}

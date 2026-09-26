package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
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
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The phone bar's dome: the ring hung [PhoneBarDomeDrop] below the tab labels' line at any font
 * scale, rising out of an 80dp bar over the stage with its name in a pill above it, and the raised
 * part still live. 360x780dp at density 2, so "within 1px" is half a dp; font scale 1.3 is the
 * user's Fold 8.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*PhoneBarDomeTest*' --rerun
 */
class PhoneBarDomeTest {
    private val name = "Space & Drift"
    private val density = 2f
    private val background = Color(0xFF14141F)

    // The bar's vibe name a size up from the tab labels' labelSmall.
    private val barNameSp = 13f
    private val tabLabelSp = 11f

    private inner class Bar(
        fontScale: Float,
        paused: Boolean = true,
        timer: TimerUiState = TimerUiState(),
        widthDp: Int = 360,
        private val heightDp: Int = 780,
        tabs: List<DjRoute> = djTabs,
        private val vibeName: String = name,
    ) {
        var toggles = 0
        var nexts = 0
        var previouses = 0
        var stageTaps = 0
        val tabClicks = mutableListOf<DjRoute>()
        var stage = Rect.Zero
        private var now = 1_000L

        private val pulsar: PulsarFeature = run {
            val base = PulsarViewModel.previewFeature()
            object : PulsarFeature by base {
                override val stateFlow: StateFlow<PulsarUiState> =
                    MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
                override val vibeNavFlow: StateFlow<VibeNavState> =
                    MutableStateFlow(VibeNavState(vibeName, "Dog House", "Stay Asleep", progress = 0.62f))
                override val actions: PulsarPanelActions =
                    base.actions.copy(nextVibe = { nexts++ }, previousVibe = { previouses++ })
            }
        }

        val scene = ImageComposeScene(widthDp * 2, heightDp * 2, Density(density, fontScale)) {
            CompositionLocalProvider(LocalTelevisionHardware provides false) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab },
                        onItemClick = { tabClicks += it },
                        layout = DjLayout.Portrait,
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(timer),
                        onTogglePlayback = { toggles++ },
                        tabs = tabs,
                        modifier = Modifier.fillMaxSize().background(background),
                    ) {
                        // Stands in for the panels: counts the taps that reach the stage.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { stage = it.boundsInRoot() }
                                .pointerInput(Unit) { detectTapGestures { stageTaps++ } },
                        )
                    }
                }
            }
        }

        fun frame(): ByteArray {
            now += 16
            return scene.render(now * 1_000_000).encodeToData()!!.bytes
        }

        init {
            frame()
            frame()
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

        private val merged get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
        private val unmerged get() = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }

        fun text(label: String): SemanticsNode = assertNotNull(
            unmerged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } },
            "no \"$label\" text",
        )

        /** The tab whose merged node reads [label]: its bounds are the M3 item's. */
        fun tab(label: String): SemanticsNode = assertNotNull(
            merged.firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            },
            "no $label tab",
        )

        /** The transport's clickable node: the ring in its 4dp padding. The name's pill is outside it. */
        val transport: SemanticsNode
            get() = assertNotNull(
                merged.firstOrNull { n ->
                    n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(", $vibeName") }
                },
                "no transport",
            )

        /** A node's rect in root px, from its position and size: unclipped, and moved by any layer's translation. */
        fun rect(node: SemanticsNode): Rect = Rect(node.positionInRoot, node.size.toSize())

        val barTop: Float get() = stage.bottom
        val barHeight: Float get() = heightDp * density - stage.bottom
        val ringBottom: Float get() = transport.positionInRoot.y + transport.size.height - TransportPadding.value * density
        val ringTop: Float get() = ringBottom - BarRingSize.value * density
        val ringCenter: Offset
            get() = transport.positionInRoot.let { Offset(it.x + transport.size.width / 2f, ringTop + BarRingSize.value * density / 2) }

        private fun layout(node: SemanticsNode): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return results.single()
        }

        fun baseline(node: SemanticsNode): Float = node.positionInRoot.y + layout(node).lastBaseline

        /** Whether [label] was cut short: ellipsized, or laid out wider than it was given. */
        fun nameCut(label: String): Boolean =
            layout(text(label)).let { it.isLineEllipsized(0) || it.size.width > it.layoutInput.constraints.maxWidth }

        /** The size the text was laid out at, in sp: the font scale applies after it. */
        fun fontSize(label: String): Float = layout(text(label)).layoutInput.style.fontSize.value

        fun pixels(): PixelMap = Image.makeFromEncoded(frame()).toComposeImageBitmap().toPixelMap()

        /** Renders on to the marquee's rest, before its first pass: a long name at its crisp start. */
        fun toMarqueeRest() = repeat(70) { frame() }

        fun tap(at: Offset, type: PointerType) {
            scene.sendPointerEvent(PointerEventType.Press, at, type = type)
            frame()
            scene.sendPointerEvent(PointerEventType.Release, at, type = type)
            frame()
        }

        fun press(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at)
            frame()
        }

        /** Moves right from [from] in 4dp steps to [dx] dp, rendering each step. */
        fun dragRight(from: Offset, dx: Int) {
            (4..dx step 4).forEach {
                scene.sendPointerEvent(PointerEventType.Move, from + Offset(it * density, 0f))
                frame()
            }
        }

        fun release(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Release, at)
            frame()
        }

        fun close() = scene.close()
    }

    /** [label]'s laid-out baseline sits on the tab labels' line, within 1px. */
    private fun assertOnTheTabLine(bar: Bar, label: String, what: String) {
        val tabLine = bar.baseline(bar.text("Mix"))
        val line = bar.baseline(bar.text(label))
        assertTrue(abs(line - tabLine) <= 1f, "$what: baseline at $line px, the tab labels' at $tabLine px")
    }

    /** The ring's bottom sits [PhoneBarDomeDrop] below the tab labels' line, within 1px. */
    private fun assertTheRingHangsTheDrop(bar: Bar, what: String) {
        val tabLine = bar.baseline(bar.text("Mix"))
        val expected = tabLine + PhoneBarDomeDrop.value * density
        assertTrue(abs(bar.ringBottom - expected) <= 1f, "$what: the ring's bottom at ${bar.ringBottom} px, not the drop below the tab labels' $tabLine px")
    }

    // The ring hangs the drop below the tab labels' line, and its name rides above it in the pill.
    @Test
    fun theRingHangsTheDropBelowTheTabLabelsLineAtBothFontScales() {
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(true, false).forEach { paused ->
                val bar = Bar(fontScale, paused = paused)
                try {
                    val what = "the ${if (paused) "paused" else "playing"} dome at $fontScale"
                    assertEquals(barNameSp, bar.fontSize(name), "$what: its name is not a size up from the tabs")
                    assertEquals(tabLabelSp, bar.fontSize("Mix"), "sanity: the tab labels moved off labelSmall")
                    assertTheRingHangsTheDrop(bar, what)
                    // The pill's bottom padding and the ring's own, in whole px as layout rounds them.
                    val underName = ((VibeNamePillPaddingY.value + TransportPadding.value) * density).roundToInt()
                    assertEquals(bar.ringTop, bar.rect(bar.text(name)).bottom + underName, 1f, "$what: the name is not just above the ring")
                } finally {
                    bar.close()
                }
            }
        }
    }

    // Over the stage the name is free of the tab labels: whole in its pill, however long, and centred on the ring.
    @Test
    fun theNameRidesAboveTheBarWholeInItsPill() {
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(name, longName).forEach { vibe ->
                val bar = Bar(fontScale, vibeName = vibe)
                try {
                    val what = "\"$vibe\" at $fontScale"
                    val line = bar.rect(bar.text(vibe))
                    assertTrue(line.bottom < bar.barTop, "$what: the name's line $line reaches into the bar at ${bar.barTop}")
                    assertTrue(!bar.nameCut(vibe), "$what: the name was cut short in its pill")
                    assertEquals(bar.ringCenter.x, line.center.x, 1f, "$what: the name is off the ring's centre")
                } finally {
                    bar.close()
                }
            }
        }
    }

    /**
     * Scrolling, the bigger name draws in an offscreen layer clipped to its laid-out line. At the
     * Fold's 1.3 it must still show every row of ink that a free-standing, unclipped Text does.
     */
    @Test
    fun theBiggerNameKeepsEveryRowOfInkAtTheFoldsScale() {
        val text = "Jgly Fog Drift"
        val scrolling = inkRows { style ->
            MarqueeLabel(text = text, style = style, color = Color.White)
        }
        val free = inkRows { style ->
            Text(
                text = text,
                style = style.copy(lineHeight = TextUnit.Unspecified),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
        assertTrue(free > 0, "the reference drew nothing, so this proves nothing")
        assertEquals(free, scrolling, "the scrolling name lost rows of ink to its line box")
    }

    /** Rows holding ink at rest, before the marquee's first pass, in a 72dp slot at font scale 1.3. */
    private fun inkRows(content: @Composable (TextStyle) -> Unit): Int {
        val scene = ImageComposeScene(72 * 2, 60 * 2, Density(density, 1.3f)) {
            OrpheusTheme {
                Box(Modifier.padding(top = 16.dp).width(72.dp)) { content(barNameStyle) }
            }
        }
        try {
            (0L..1_088L step 16).forEach { scene.render(it * 1_000_000) }
            val pixels = Image.makeFromEncoded(scene.render(1_104L * 1_000_000).encodeToData()!!.bytes)
                .toComposeImageBitmap().toPixelMap()
            return (0 until pixels.height).count { y -> (0 until pixels.width).any { x -> pixels[x, y].alpha > 0.05f } }
        } finally {
            scene.close()
        }
    }

    // The Timer tab's countdown sits in its icon slot, above its label; the label still sets its line.
    @Test
    fun aRunningTimerTabKeepsTheLine() {
        listOf(1f, 1.3f).forEach { fontScale ->
            val bar = Bar(fontScale, timer = TimerUiState(remainingTime = 53.seconds, status = TimerStatus.RUNNING))
            try {
                // The countdown must not grow the bar either.
                assertEquals(bar.tab("Mix").size.height.toFloat(), bar.barHeight, 1f, "the bar under a running timer at $fontScale")
                assertOnTheTabLine(bar, "Timer", "the counting-down Timer tab at $fontScale")
                assertTheRingHangsTheDrop(bar, "the dome beside a running timer at $fontScale")
            } finally {
                bar.close()
            }
        }
    }

    // A drag far enough to peek, short of the commit: the peek row takes the name's line in the pill.
    @Test
    fun thePeekTakesTheNamesLineInThePill() {
        listOf(1f, 1.3f).forEach { fontScale ->
            val bar = Bar(fontScale)
            try {
                val resting = bar.baseline(bar.text(name))
                val start = bar.ringCenter
                bar.press(start)
                bar.dragRight(start, 24)
                val peeked = bar.baseline(bar.text("Stay Asleep"))
                assertTrue(abs(peeked - resting) <= 1f, "the peeked name at $fontScale: baseline $peeked px, the name's $resting px")
                // Name and arrow both stay a size up, so the text never jumps size mid-drag.
                assertEquals(barNameSp, bar.fontSize("Stay Asleep"), "the peeked name at $fontScale")
                assertEquals(barNameSp, bar.fontSize(" ›"), "the peek's arrow at $fontScale")
                bar.release(start + Offset(24 * density, 0f))
                assertEquals(0, bar.nexts, "a 24dp drag committed")
            } finally {
                bar.close()
            }
        }
    }

    private val longName = "Kaleidoscope Drift Sessions"

    // The pill is only a label over the stage: a tap on it reaches the panel under it.
    @Test
    fun aTapOnThePillReachesTheStage() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val bar = Bar(1f)
            try {
                val onName = bar.rect(bar.text(name)).center
                assertTrue(onName.y < bar.rect(bar.transport).top, "sanity: the name $onName is inside the dome's target")
                bar.tap(onName, type)
                assertEquals(1, bar.stageTaps, "a $type tap on the pill never reached the stage")
                assertEquals(0, bar.toggles, "a $type tap on the pill toggled playback")
            } finally {
                bar.close()
            }
        }
    }

    @Test
    fun theBarKeepsItsHeight() {
        val bar = Bar(1f)
        try {
            assertEquals(80f * density, bar.barHeight, 1f, "the bar at font scale 1")
        } finally {
            bar.close()
        }
        // At the Fold's scale the bar is what M3's items need, not what the ring and its name do.
        val large = Bar(1.3f)
        try {
            assertEquals(large.tab("Mix").size.height.toFloat(), large.barHeight, 1f, "the bar at font scale 1.3")
        } finally {
            large.close()
        }
    }

    @Test
    fun theDomeRisesOutOfTheBar() {
        val bar = Bar(1f)
        try {
            val rise = (bar.barTop - bar.ringTop) / density
            assertTrue(rise >= 12f, "the ring's top is only ${rise}dp above the bar")
            // Drawn, not clipped: the ring's own pixels reach at least 12dp up the centre column.
            val pixels = bar.pixels()
            val x = bar.ringCenter.x.toInt()
            val firstDrawn = (0 until bar.barTop.toInt()).firstOrNull { y ->
                val p = pixels[x, y]
                abs(p.red - background.red) + abs(p.green - background.green) + abs(p.blue - background.blue) > 0.1f
            }
            assertNotNull(firstDrawn, "nothing is drawn above the bar")
            val drawnRise = (bar.barTop - firstDrawn) / density
            assertTrue(drawnRise >= 12f, "the ring draws only ${drawnRise}dp above the bar")
        } finally {
            bar.close()
        }
    }

    @Test
    fun aTapOnTheRaisedDomeTogglesPlayback() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            listOf(1f, 1.3f).forEach { fontScale ->
                val bar = Bar(fontScale)
                try {
                    // Midway up the ring's part above the bar.
                    val at = Offset(bar.ringCenter.x, (bar.ringTop + bar.barTop) / 2)
                    assertTrue(at.y > bar.ringTop && at.y < bar.barTop, "the tap at $at is not on the ring above the bar")
                    bar.tap(at, type)
                    assertEquals(1, bar.toggles, "$type tap on the raised dome at $fontScale")
                    assertEquals(0, bar.stageTaps, "the raised dome's tap reached the stage")
                } finally {
                    bar.close()
                }
            }
        }
    }

    @Test
    fun aTapBesideTheRaisedDomeReachesTheStage() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val bar = Bar(1f)
            try {
                val y = bar.barTop - 8 * density
                val clear = (BarRingSize.value / 2 + 10) * density
                listOf(-clear, clear).forEach { dx ->
                    bar.tap(Offset(bar.ringCenter.x + dx, y), type)
                }
                assertEquals(0, bar.toggles, "a $type tap beside the dome toggled playback")
                assertEquals(2, bar.stageTaps, "a $type tap beside the dome never reached the stage")
            } finally {
                bar.close()
            }
        }
    }

    // ==================== over faded panels ====================

    /**
     * The slice of `DjAppScreen` that owns the fade (root observer, layout box, watcher, wake
     * overlay) around the real scaffold, on virtual clocks as DjAppMainContentWiringTest drives it.
     */
    private inner class FadedBar {
        var nowMs = 0L
        var toggles = 0
        var stageTaps = 0
        val stage = VizStage()
        val fade = PanelIdleFade { nowMs }
        private val scheduler = TestCoroutineScheduler()

        val scene = ImageComposeScene(
            360 * 2, 780 * 2, Density(density), UnconfinedTestDispatcher(scheduler),
        ) {
            OrpheusTheme {
                CompositionLocalProvider(LocalVizStage provides stage, LocalPanelIdleFade provides fade) {
                    Box(Modifier.fillMaxSize().background(background).panelActivityObserver(fade, enabled = true)) {
                        DjLayoutBox(Modifier.fillMaxSize()) { layout ->
                            PanelIdleFadeWatcher(fade, enabled = true)
                            DjAppNavScaffold(
                                isSelected = { it == DjTab },
                                onItemClick = {},
                                layout = layout,
                                pulsarFeature = PulsarViewModel.previewFeature(),
                                timerFeature = TimerViewModel.previewFeature(),
                                onTogglePlayback = { toggles++ },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .vizStage(stage)
                                        .panelIdleFade(fade)
                                        .pointerInput(Unit) { detectTapGestures { stageTaps++ } },
                                )
                            }
                            PanelWakeOverlay(fade, stage, enabled = true)
                        }
                    }
                }
            }
        }

        init {
            // DjLayoutBox picks a layout from the previous frame's size.
            repeat(2) { step(0) }
        }

        fun step(ms: Long) {
            nowMs += ms
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
            scene.render(nowMs * 1_000_000L)
        }

        fun fadeOut() {
            repeat(((PanelIdleTimeoutMs + PanelFadeOutMs) / 16 + 4).toInt()) { step(16) }
            assertTrue(fade.alpha.value < 0.01f, "the panels never faded: ${fade.alpha.value}")
        }

        fun tap(at: Offset, type: PointerType) {
            scene.sendPointerEvent(PointerEventType.Press, at, type = type)
            step(16)
            scene.sendPointerEvent(PointerEventType.Release, at, type = type)
            step(16)
        }

        /** The transport's pointer column, ring and name, from its semantics: position plus size, so unclipped. */
        val transport: Rect
            get() {
                fun SemanticsNode.find(): SemanticsNode? =
                    if (config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Next vibe" }) this
                    else children.firstNotNullOfOrNull { it.find() }
                val node = assertNotNull(scene.semanticsOwners.firstNotNullOfOrNull { it.rootSemanticsNode.find() }, "no transport")
                return Rect(node.positionInRoot, node.size.toSize())
            }

        val barTop: Float get() = assertNotNull(stage.bounds, "no stage").bottom

        fun resize(widthDp: Int, heightDp: Int) {
            scene.constraints = Constraints.fixed((widthDp * density).toInt(), (heightDp * density).toInt())
            repeat(3) { step(16) }
        }

        fun close() = scene.close()
    }

    // The dome reports its whole pointer column, raised part included, so the overlay leaves it live.
    @Test
    fun theBarReportsTheWholeDomeAsChromeOverTheStage() {
        val bar = FadedBar()
        try {
            val overhang = assertNotNull(bar.stage.chromeOverhang, "the bar reported no overhang")
            assertEquals(bar.transport, overhang, "the overhang is not the transport's pointer column")
            val rise = (bar.barTop - overhang.top) / density
            assertTrue(rise >= 12f, "the overhang reaches only ${rise}dp above the bar, so it was clipped")
        } finally {
            bar.close()
        }
    }

    @Test
    fun overFadedPanelsTheRaisedDomeStillToggles() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val bar = FadedBar()
            try {
                val dome = bar.transport
                val y = bar.barTop - 8 * density
                val clear = (BarRingSize.value / 2 + 10) * density

                bar.fadeOut()
                bar.tap(Offset(dome.center.x, y), type)
                assertEquals(1, bar.toggles, "$type: a tap on the raised dome over faded panels")
                assertEquals(0, bar.stageTaps)

                // Beside the dome the overlay still eats the tap, and the tap wakes the panels.
                bar.fadeOut()
                bar.tap(Offset(dome.center.x - clear, y), type)
                assertTrue(dome.left > dome.center.x - clear, "sanity: the beside tap landed on the dome")
                assertEquals(1, bar.toggles, "$type: a tap beside the dome toggled playback")
                assertEquals(0, bar.stageTaps, "$type: a tap beside the dome reached a faded panel")
                repeat((PanelFadeInMs / 16) + 4) { bar.step(16) }
                assertEquals(1f, bar.fade.alpha.value, "$type: the tap beside the dome did not wake the panels")

                bar.fadeOut()
                bar.tap(Offset(dome.center.x, bar.barTop + 20 * density), type)
                assertEquals(2, bar.toggles, "$type: a tap on the dome inside the bar over faded panels")
            } finally {
                bar.close()
            }
        }
    }

    // The rail has no raised ring: a stale rect there would punch a hole over faded panels.
    @Test
    fun theOverhangClearsWhenTheRailTakesOver() {
        val bar = FadedBar()
        try {
            assertNotNull(bar.stage.chromeOverhang, "the bar reported no overhang")
            bar.resize(780, 360)
            assertEquals(null, bar.stage.chromeOverhang, "the rail kept the bar's overhang")
            bar.resize(360, 780)
            assertNotNull(bar.stage.chromeOverhang, "back in the bar, the overhang never returned")
        } finally {
            bar.close()
        }
    }

    @Test
    fun aSwipeFromTheRaisedPartNavigates() {
        val bar = Bar(1f)
        try {
            val start = Offset(bar.ringCenter.x, bar.barTop - 8 * density)
            bar.press(start)
            bar.dragRight(start, 48)
            bar.release(start + Offset(48 * density, 0f))
            assertEquals(1, bar.nexts, "a swipe right from the raised part")
            assertEquals(0, bar.previouses)
            assertEquals(0, bar.toggles, "the swipe also toggled playback")
            assertEquals(0, bar.stageTaps, "the swipe reached the stage")
        } finally {
            bar.close()
        }
    }
}

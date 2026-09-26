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
 * The phone bar's dome: the vibe name on the tab labels' line at any font scale, the ring rising
 * out of an 80dp bar over the stage, and the raised part still live. 360x780dp at density 2, so
 * "within 1px" is half a dp; font scale 1.3 is the user's Fold 8.
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
        // TV hardware: the one place a name too long for its lane still ellipsizes.
        tv: Boolean = false,
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
            CompositionLocalProvider(LocalTelevisionHardware provides tv) {
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

        /** The transport's clickable node: 4dp padding, the ring, the name, 4dp padding. */
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
        val ringTop: Float get() = transport.positionInRoot.y + TransportPadding.value * density
        val ringCenter: Offset
            get() = transport.positionInRoot.let { Offset(it.x + transport.size.width / 2f, ringTop + BarRingSize.value * density / 2) }

        private fun layout(node: SemanticsNode): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return results.single()
        }

        fun baseline(node: SemanticsNode): Float = node.positionInRoot.y + layout(node).lastBaseline

        /** The size the text was laid out at, in sp: the font scale applies after it. */
        fun fontSize(label: String): Float = layout(text(label)).layoutInput.style.fontSize.value

        fun pixels(): PixelMap = Image.makeFromEncoded(frame()).toComposeImageBitmap().toPixelMap()

        /** Renders on to the marquee's rest, before its first pass: a long name at its crisp start. */
        fun toMarqueeRest() = repeat(70) { frame() }

        /**
         * The ink on the tab labels' line, as px columns: the name's purple and the labels' grey,
         * told apart by colour over the bar's dark ground. Rows well inside the labels' line, where
         * the labels' glyphs and the dropped name's glyphs both have ink, and clear of the ring.
         */
        fun lineInk(): LineInk {
            val pixels = pixels()
            val line = rect(text("Mix"))
            val top = (line.top + 3 * density).toInt()
            val bottom = (line.bottom - 3 * density).toInt()
            val nameInk = mutableListOf<Int>()
            val labelInk = mutableListOf<Int>()
            for (x in 0 until pixels.width) {
                var isName = false
                var isLabel = false
                for (y in top..bottom) {
                    val p = pixels[x, y]
                    val r = p.red * 255; val g = p.green * 255; val b = p.blue * 255
                    // Purple leans blue; the grey labels (and the dark ground, 20/20/31) barely do.
                    if (b - g > 28f) isName = true
                    else if (maxOf(r, g, b) - 31f > 20f && abs(b - g) < 18f && abs(r - g) < 18f) isLabel = true
                }
                if (isName) nameInk += x else if (isLabel) labelInk += x
            }
            return LineInk(nameInk, labelInk)
        }

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

    /** Ink columns on the labels' line, in px: the vibe name's, and every tab label's. */
    private class LineInk(val name: List<Int>, val labels: List<Int>)

    /** [label]'s laid-out baseline sits on the tab labels' line, within 1px. */
    private fun assertOnTheTabLine(bar: Bar, label: String, what: String) {
        val tabLine = bar.baseline(bar.text("Mix"))
        val line = bar.baseline(bar.text(label))
        assertTrue(abs(line - tabLine) <= 1f, "$what: baseline at $line px, the tab labels' at $tabLine px")
    }

    private val drop get() = BarNameDrop.value

    // Laid out on the tab labels' line with the ring right on it, the drop being draw-only:
    // theNameIsDrawnTheDropBelowItsLine checks where it is drawn.
    @Test
    fun theNameIsLaidOutOnTheTabLabelsLineAtBothFontScales() {
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(true, false).forEach { paused ->
                val bar = Bar(fontScale, paused = paused)
                try {
                    // Paused or playing, a name too long for the lane scrolls.
                    val what = "the ${if (paused) "paused" else "playing"} name at $fontScale"
                    assertEquals(barNameSp, bar.fontSize(name), "$what is not a size up from the tabs")
                    assertEquals(tabLabelSp, bar.fontSize("Mix"), "sanity: the tab labels moved off labelSmall")
                    assertOnTheTabLine(bar, name, what)
                    val ringBottom = bar.ringTop + BarRingSize.value * density
                    assertEquals(ringBottom, bar.rect(bar.text(name)).top, 1f, "$what: the ring left the name's laid-out line")
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
                assertOnTheTabLine(bar, name, "the name beside a running timer at $fontScale")
            } finally {
                bar.close()
            }
        }
    }

    // A drag far enough to peek, short of the commit: the peek row takes the name's dropped line.
    @Test
    fun thePeekSitsOnTheTabLabelsLine() {
        listOf(1f, 1.3f).forEach { fontScale ->
            val bar = Bar(fontScale)
            try {
                val start = bar.ringCenter
                bar.press(start)
                bar.dragRight(start, 24)
                assertOnTheTabLine(bar, "Stay Asleep", "the peeked name at $fontScale")
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

    // ==================== the name's lane and drop ====================

    private val longName = "Kaleidoscope Drift Sessions"

    /** The AI edition's tabs: AI takes Horn's place beside the transport. */
    private val aiTabs = listOf(DjTab, MixTab, AiTab, TimerTab)

    /**
     * The drawn name sits exactly the drop lower, resting (TV hardware's ellipsis), scrolling and
     * peeked: the same transport with and without it, its ink rows shifted by the drop and nothing
     * else. The ring and the name's laid-out line stay put (theNameIsLaidOutOnTheTabLabelsLine...),
     * so that is also how much the gap under the ring grows.
     */
    @Test
    fun theNameIsDrawnTheDropBelowItsLine() {
        val dropPx = (drop * density).roundToInt()
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf("resting", "scrolling", "peeked").forEach { state ->
                val flat = nameInkRows(0.dp, state, fontScale)
                val dropped = nameInkRows(BarNameDrop, state, fontScale)
                assertEquals(flat.first + dropPx, dropped.first, "the $state name's ink top at $fontScale")
                assertEquals(flat.last + dropPx, dropped.last, "the $state name's ink bottom at $fontScale")
            }
        }
    }

    /** The rows the name's ink spans under the ring, the transport alone in the bar's style and a wider lane. */
    private fun nameInkRows(nameDrop: Dp, state: String, fontScale: Float): IntRange {
        val text = "Space & Drift Sessions"
        val scene = ImageComposeScene(120 * 2, 120 * 2, Density(density, fontScale)) {
            CompositionLocalProvider(LocalTelevisionHardware provides (state == "resting")) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(background)) {
                        VibeTransportItem(
                            name = text, previousName = "Dog House", nextName = text, progress = 0.62f,
                            paused = true, onTogglePlayback = {}, onNext = {}, onPrevious = {},
                            // A 66dp slot and a 99dp lane, both inside the scene.
                            modifier = Modifier.padding(start = 27.dp).width(66.dp),
                            nameStyle = barNameStyle, nameLane = { it * 3 / 2 }, nameDrop = nameDrop,
                            previewDragDp = if (state == "peeked") 24f else 0f,
                        )
                    }
                }
            }
        }
        try {
            // On to the marquee's rest, before its first pass.
            (0L..1_088L step 16).forEach { scene.render(it * 1_000_000) }
            val pixels = Image.makeFromEncoded(scene.render(1_104L * 1_000_000).encodeToData()!!.bytes)
                .toComposeImageBitmap().toPixelMap()
            // Below the ring's box, so the purple dome is out of it.
            val top = ((TransportPadding + BarRingSize).value * density).toInt() + 1
            val rows = (top until pixels.height).filter { y ->
                (0 until pixels.width).any { x -> pixels[x, y].let { (it.blue - it.green) * 255 > 28f } }
            }
            assertTrue(rows.isNotEmpty(), "the $state name drew no ink")
            return rows.first()..rows.last()
        } finally {
            scene.close()
        }
    }

    /**
     * The name's ink keeps [BarNameClearance] from the labels either side, wherever it runs longest:
     * both widths, both font scales, both editions, paused and playing at the marquee's rest. The
     * Timer, whose countdown can change its label's tab, is never beside the transport in either edition.
     */
    @Test
    fun theNameKeepsItsDistanceFromTheNeighbouringLabels() {
        for ((widthDp, heightDp) in listOf(360 to 780, 412 to 915)) for (fontScale in listOf(1f, 1.3f)) for (tabs in listOf(djTabs, aiTabs)) {
            for (vibe in listOf(name, longName)) for (paused in listOf(true, false)) {
                val what = "\"$vibe\" ${if (paused) "paused" else "playing"} at marquee rest at ${widthDp}dp, font scale $fontScale, " +
                    tabs.joinToString("/") { it.label }
                val bar = Bar(fontScale, paused = paused, widthDp = widthDp, heightDp = heightDp, tabs = tabs, vibeName = vibe)
                try {
                    bar.toMarqueeRest()
                    val ink = bar.lineInk()
                    assertTrue(ink.name.isNotEmpty(), "$what: no name ink")
                    val left = assertNotNull(ink.labels.filter { it < ink.name.min() }.maxOrNull(), "$what: no label ink left of the name")
                    val right = assertNotNull(ink.labels.filter { it > ink.name.max() }.minOrNull(), "$what: no label ink right of the name")
                    val clearance = BarNameClearance.value
                    val leftGap = (ink.name.min() - left - 1) / density
                    val rightGap = (right - ink.name.max() - 1) / density
                    assertTrue(leftGap >= clearance, "$what: ${leftGap}dp from the left label")
                    assertTrue(rightGap >= clearance, "$what: ${rightGap}dp from the right label")
                } finally {
                    bar.close()
                }
            }
        }
    }

    // The lane is drawing only: the transport's tap target keeps its slot, so a tap on the name's
    // overhang selects the tab beneath it, on either side (the earlier and the later sibling).
    @Test
    fun aTapOnTheNamesOverhangSelectsTheTabBeneathIt() {
        listOf(PointerType.Mouse, PointerType.Touch).forEach { type ->
            val bar = Bar(1.3f, vibeName = longName)
            try {
                // Where the scrolling name draws: at the marquee's rest its ink spans its lane.
                bar.toMarqueeRest()
                val ink = bar.lineInk().name
                val slot = bar.rect(bar.transport)
                assertTrue(ink.min() < slot.left && ink.max() > slot.right, "sanity: the name draws no wider than its slot")
                val y = bar.rect(bar.text(longName)).center.y
                val overMix = Offset(ink.min() + 2 * density, y)
                val overHorn = Offset(ink.max() - 2 * density, y)
                assertTrue(bar.rect(bar.tab("Mix")).contains(overMix), "sanity: the lane never reaches over Mix")
                assertTrue(bar.rect(bar.tab("Horn")).contains(overHorn), "sanity: the lane never reaches over Horn")
                bar.tap(overMix, type)
                bar.tap(overHorn, type)
                assertEquals(listOf<DjRoute>(MixTab, HornTab), bar.tabClicks, "$type taps on the name's overhang")
                assertEquals(0, bar.toggles, "a $type tap on the name's overhang toggled playback")
            } finally {
                bar.close()
            }
        }
    }

    /**
     * Dropped, the scrolling name still shows every row of ink the plain one does (TV hardware's
     * ellipsis): the marquee's clip and offscreen layer move with the drop rather than cutting its glyphs.
     */
    @Test
    fun theDroppedNameKeepsEveryRowOfInk() {
        val text = "Jgly Fog Drift"
        fun inkRows(tv: Boolean): Int {
            val bar = Bar(1.3f, vibeName = text, tv = tv)
            try {
                bar.toMarqueeRest()
                val pixels = bar.pixels()
                // Below the ring's box, so the purple dome is out of it; across the bar's width.
                val top = (bar.ringTop + BarRingSize.value * density).toInt()
                return (top until pixels.height).count { y ->
                    (0 until pixels.width).any { x -> pixels[x, y].let { (it.blue - it.green) * 255 > 28f } }
                }
            } finally {
                bar.close()
            }
        }
        val plain = inkRows(tv = true)
        assertTrue(plain > 0, "the plain name drew nothing, so this proves nothing")
        assertEquals(plain, inkRows(tv = false), "the scrolling name lost rows of ink")
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
                    val domeTop = bar.ringTop + domeInset(BarRingSize).value * density
                    val at = Offset(bar.ringCenter.x, (domeTop + bar.barTop) / 2)
                    assertTrue(at.y > domeTop && at.y < bar.barTop, "the tap at $at is not on the dome above the bar")
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

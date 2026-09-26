package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarqueeLabelTest {

    // ---- marqueeActive: an overflowing name scrolls only off TV ----

    @Test
    fun anOverflowingNameScrollsOnlyOffTelevision() {
        assertTrue(marqueeActive(televisionHardware = false))
        assertFalse(marqueeActive(televisionHardware = true))
    }

    // ---- the edge fades follow the scroll ----
    // A 200px name in a 100px slot, the second copy 25px behind it, fading over 10px.

    private fun left(offset: Float) = marqueeFadeLeft(offsetPx = offset, textPx = 200f, gapPx = 25f, fadePx = 10f)

    private fun right(offset: Float) = marqueeFadeRight(offsetPx = offset, textPx = 200f, gapPx = 25f, slotPx = 100f, fadePx = 10f)

    @Test
    fun atRestOnlyTheRightEdgeFades() {
        assertEquals(0f, left(0f))
        assertEquals(1f, right(0f))
    }

    @Test
    fun theLeftFadeGrowsInWithTheOffset() {
        assertEquals(0.5f, left(5f), 1e-6f)
        assertEquals(1f, left(10f), 1e-6f)
        assertEquals(1f, left(150f), 1e-6f)
    }

    @Test
    fun theRightFadeEasesOutAsTheEndComesIntoView() {
        // The first copy's end reaches the right edge at offset 100.
        assertEquals(1f, right(90f), 1e-6f)
        assertEquals(0.5f, right(95f), 1e-6f)
        assertEquals(0f, right(100f), 1e-6f)
    }

    @Test
    fun theRightFadeReturnsAsTheSecondCopyArrives() {
        // The second copy starts at 225 - offset: it reaches the fade at 115 and the edge at 125.
        assertEquals(0f, right(115f), 1e-6f)
        assertEquals(0.5f, right(120f), 1e-6f)
        assertEquals(1f, right(125f), 1e-6f)
    }

    @Test
    fun theLeftFadeEasesOutAsThePassWrapsBackToTheStart() {
        assertEquals(0.5f, left(220f), 1e-6f)
        // At 225 the second copy sits where the first began, so the frame is the rest frame again.
        assertEquals(left(0f), left(225f), 1e-6f)
        assertEquals(right(0f), right(225f), 1e-6f)
    }

    @Test
    fun aNameThatBarelyOverflowsHintsWithALighterFade() {
        assertEquals(0.4f, marqueeFadeRight(offsetPx = 0f, textPx = 104f, gapPx = 25f, slotPx = 100f, fadePx = 10f), 1e-6f)
    }

    // ---- MarqueeLabel scenes ----

    private val longName = "Kaleidoscope Drift Sessions"
    private val shortName = "Dog House"

    private fun labelScene(
        text: String,
        tv: Boolean = false,
        widthDp: Int = 72,
        context: CoroutineContext = Dispatchers.Unconfined,
    ) = ImageComposeScene(widthDp * 2, 40, Density(2f), context) {
        CompositionLocalProvider(LocalTelevisionHardware provides tv) {
            OrpheusTheme {
                Box(Modifier.width(widthDp.dp)) {
                    MarqueeLabel(text = text, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }

    private fun ImageComposeScene.frame(ms: Long): ByteArray = render(ms * 1_000_000).encodeToData()!!.bytes

    private fun ImageComposeScene.pixels(ms: Long): PixelMap =
        Image.makeFromEncoded(frame(ms)).toComposeImageBitmap().toPixelMap()

    /** Renders frames at [ms] until the scene asks for none; how many it took, or null if it never settled. */
    private fun ImageComposeScene.settle(ms: Long, maxFrames: Int = 10): Int? =
        (1..maxFrames).firstOrNull { frame(ms); !hasInvalidations() }

    // The same name drawn plainly and clipped at the slot: no marquee, no fades.
    private fun clippedTextScene(text: String, widthDp: Int = 72) =
        ImageComposeScene(widthDp * 2, 40, Density(2f)) {
            OrpheusTheme {
                Box(Modifier.width(widthDp.dp)) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }

    // The user's report: at rest the left fade smeared the first letter. Before the first pass the
    // text sits at its start, so the left edge must draw exactly as unfaded text does.
    @Test
    fun theFirstGlyphIsCrispAtRest() {
        val marquee = labelScene(longName)
        val plain = clippedTextScene(longName)
        try {
            (0L..1_088L step 16).forEach { marquee.frame(it) }
            val rest = marquee.pixels(1_104)
            val reference = plain.pixels(0)
            val fadePx = 14 // MarqueeFadeWidth at density 2
            for (x in 0 until fadePx) for (y in 0 until rest.height) {
                assertEquals(reference[x, y].alpha, rest[x, y].alpha, 0.02f, "pixel ($x, $y) is faded at rest")
            }
            val firstGlyph = (0 until fadePx).maxOf { x -> (0 until rest.height).maxOf { y -> rest[x, y].alpha } }
            assertTrue(firstGlyph > 0.98f, "the first glyph peaks at alpha $firstGlyph at rest")
            // Non-vacuous: this is the scrolling label, whose right edge hints at more text.
            val right = rest.width - 1
            assertTrue(
                (0 until rest.height).any { y -> reference[right, y].alpha - rest[right, y].alpha > 0.2f },
                "the right edge carries no fade, so the label was not in marquee mode",
            )
        } finally {
            marquee.close()
            plain.close()
        }
    }

    @Test
    fun aLongNameKeepsScrolling() {
        val scene = labelScene(longName)
        try {
            (0L..1_200L step 16).forEach { scene.frame(it) }
            val before = scene.frame(1_216)
            (1_232L..2_000L step 16).forEach { scene.frame(it) }
            assertTrue(scene.hasInvalidations(), "a long label should keep asking for frames")
            assertFalse(before.contentEquals(scene.frame(2_016)), "the label never moved")
        } finally {
            scene.close()
        }
    }

    // A name that fits is the plain Text, still: no frames, no fades, never offset.
    @Test
    fun aNameThatFitsIsStill() {
        val scene = labelScene(shortName)
        val plain = plainEllipsisScene(shortName)
        try {
            (0L..300L step 16).forEach { scene.frame(it) }
            assertFalse(scene.hasInvalidations(), "a name that fits should not animate")
            val held = scene.frame(316)
            assertTrue(held.contentEquals(scene.frame(3_000)), "a name that fits moved on its own")
            assertTrue(held.contentEquals(plain.frame(0)), "a name that fits should draw as the plain Text")
        } finally {
            scene.close()
            plain.close()
        }
    }

    // The same text/width/style rendered as a plain ellipsized Text, with no marquee at all — the
    // exact reference for "shows today's resting ellipsis".
    private fun plainEllipsisScene(text: String, widthDp: Int = 72) =
        ImageComposeScene(widthDp * 2, 40, Density(2f)) {
            OrpheusTheme {
                Box(Modifier.width(widthDp.dp)) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

    // TV hardware keeps the ellipsis: measured by nothing, animated by nothing.
    @Test
    fun televisionHardwareShowsTheEllipsisAndAsksForNoFrames() {
        val marquee = labelScene(longName, tv = true)
        val plain = plainEllipsisScene(longName)
        try {
            (0L..2_000L step 16).forEach { marquee.frame(it) }
            assertFalse(marquee.hasInvalidations(), "a TV label should never animate")
            assertTrue(marquee.frame(2_016).contentEquals(plain.frame(0)), "a TV label should match the plain ellipsis exactly")
        } finally {
            marquee.close()
            plain.close()
        }
    }

    // A new name is judged on its own width: one that now fits stops, one that now overflows scrolls.
    @Test
    fun aChangedNameIsMeasuredAfresh() {
        var name by mutableStateOf(longName)
        val scene = ImageComposeScene(144, 40, Density(2f)) {
            OrpheusTheme {
                Box(Modifier.width(72.dp)) {
                    MarqueeLabel(text = name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        fun change(newName: String) {
            name = newName
            Snapshot.sendApplyNotifications()
        }
        try {
            (0L..2_000L step 16).forEach { scene.frame(it) }
            assertTrue(scene.hasInvalidations(), "the long name should be scrolling")

            change(shortName)
            assertTrue(scene.settle(2_016) != null, "a name that now fits kept scrolling")

            change(longName)
            (2_048L..3_264L step 16).forEach { scene.frame(it) }
            val before = scene.frame(3_280)
            (3_296L..4_000L step 16).forEach { scene.frame(it) }
            assertTrue(scene.hasInvalidations(), "a name that now overflows should scroll")
            assertFalse(before.contentEquals(scene.frame(4_016)), "a name that now overflows never moved")
        } finally {
            scene.close()
        }
    }

    // Between passes the name rests on a delay, a test scheduler's here, not on frames; it rests
    // drawn exactly as it was before the first pass.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theMarqueeRestsBetweenPassesWithoutFrames() {
        val scheduler = TestCoroutineScheduler()
        val scene = labelScene(longName, context = UnconfinedTestDispatcher(scheduler))
        try {
            (0L..1_088L step 16).forEach { scene.frame(it) }
            val beforeFirstPass = scene.frame(1_104)
            var ms = 1_104L
            while (scene.hasInvalidations()) {
                ms += 16
                assertTrue(ms < 20_000L, "the first pass never ended")
                scene.frame(ms)
            }
            assertTrue(ms > 3_000L, "the label stopped before it scrolled a pass, so this proves nothing")
            assertTrue(beforeFirstPass.contentEquals(scene.frame(ms + 16)), "the rest is not drawn as the pass began")
            assertFalse(scene.hasInvalidations(), "the rest asked for frames")
            scheduler.advanceTimeBy(MarqueeRepeatDelayMillis.toLong())
            scheduler.runCurrent()
            assertTrue(scene.hasInvalidations(), "the next pass never began after the rest")
        } finally {
            scene.close()
        }
    }

    // A counting draw in a layer around the label stands in for the rail, bar or dock glass.
    @Test
    fun aScrollingLabelNeverReRecordsWhatSurroundsIt() {
        var surroundDraws = 0
        val scene = ImageComposeScene(72 * 2, 40, Density(2f)) {
            OrpheusTheme {
                Box(Modifier.graphicsLayer().drawBehind { surroundDraws++ }) {
                    Box(Modifier.width(72.dp)) {
                        MarqueeLabel(text = longName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        try {
            (0L..1_200L step 16).forEach { scene.frame(it) }
            val baseline = surroundDraws
            val before = scene.frame(1_216)
            (1_232L..2_000L step 16).forEach { scene.frame(it) }
            assertFalse(before.contentEquals(scene.frame(2_016)), "the label never scrolled, so this proves nothing")
            assertEquals(baseline, surroundDraws, "a scroll frame re-recorded the layer around the label")
        } finally {
            scene.close()
        }
    }

    // The real transport, ring and dome included, playing a long name: a scroll frame re-records
    // the label's layer alone and recomposes nothing.
    @OptIn(ExperimentalComposeRuntimeApi::class)
    @Test
    fun theTransportsScrollingLabelNeitherReRecordsNorRecomposesWhatSurroundsIt() {
        var surroundDraws = 0
        val scopes = ScopeCounter()
        var observed = false
        var name by mutableStateOf(longName)
        val scene = ImageComposeScene(88 * 2, 120 * 2, Density(2f)) {
            val composition = currentComposer.composition
            DisposableEffect(composition) {
                val handle = composition.setObserver(scopes)
                observed = handle != null
                onDispose { handle?.dispose() }
            }
            OrpheusTheme {
                Box(Modifier.graphicsLayer().drawBehind { surroundDraws++ }) {
                    VibeTransportItem(
                        name = name,
                        previousName = "Dog House",
                        nextName = "Stay Asleep",
                        progress = 0.62f,
                        paused = false,
                        onTogglePlayback = {},
                        onNext = {},
                        onPrevious = {},
                        modifier = Modifier.width(80.dp).padding(horizontal = 4.dp),
                        centerLabel = true,
                        ringSize = RailRingSize,
                    )
                }
            }
        }
        try {
            (0L..1_200L step 16).forEach { scene.frame(it) }
            assertTrue(observed, "the composition could not be observed, so this proves nothing")
            val drawsBefore = surroundDraws
            val scopesBefore = scopes.entered
            val before = scene.frame(1_216)
            (1_232L..2_000L step 16).forEach { scene.frame(it) }
            assertFalse(before.contentEquals(scene.frame(2_016)), "the transport never moved, so this proves nothing")
            assertEquals(drawsBefore, surroundDraws, "a scroll frame re-recorded the layer around the transport")
            assertEquals(scopesBefore, scopes.entered, "a scroll frame recomposed")
            // The counter does see a real recomposition.
            name = shortName
            Snapshot.sendApplyNotifications()
            scene.frame(2_032)
            assertTrue(scopes.entered > scopesBefore, "a name change recomposed nothing, so the counter is blind")
        } finally {
            scene.close()
        }
    }

    // Paused, the transport scrolls a name too long for its slot as it does playing, and holds one that fits.
    @Test
    fun aPausedTransportScrollsALongNameAndHoldsOneThatFits() {
        fun transport(name: String) = ImageComposeScene(88 * 2, 120 * 2, Density(2f)) {
            OrpheusTheme {
                VibeTransportItem(
                    name = name,
                    previousName = "Dog House",
                    nextName = "Stay Asleep",
                    // No progress, so no zip: only the name can ask for frames.
                    progress = null,
                    paused = true,
                    onTogglePlayback = {},
                    onNext = {},
                    onPrevious = {},
                    modifier = Modifier.width(80.dp).padding(horizontal = 4.dp),
                    centerLabel = true,
                    ringSize = RailRingSize,
                )
            }
        }
        val long = transport(longName)
        try {
            (0L..1_200L step 16).forEach { long.frame(it) }
            val before = long.frame(1_216)
            (1_232L..2_000L step 16).forEach { long.frame(it) }
            assertTrue(long.hasInvalidations(), "a paused long name should keep scrolling")
            assertFalse(before.contentEquals(long.frame(2_016)), "a paused long name never moved")
        } finally {
            long.close()
        }
        val short = transport(shortName)
        try {
            assertTrue(short.settle(1_000) != null, "a paused name that fits asked for frames")
        } finally {
            short.close()
        }
    }

    // ---- Dock: step tiles scroll long neighbour names without recomposing the bar ----

    // Deliberately long, so the step tiles overflow their slot regardless of exact panel count.
    private val longNavState = VibeNavState(
        currentName = "Ouroboros Bloom",
        previousName = "Kaleidoscope Drift Extended Midnight Session Redux",
        nextName = "Vanished Skyline Under A Thousand Stars Tonight",
        progress = 0.1f,
    )

    private fun dockPulsar(paused: Boolean, nav: VibeNavState = longNavState): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
        }
    }

    /**
     * A bar scene whose [compositions] count runs of DjTvBottomBar's own body, as in
     * ProgressWaveTest's BarScene, 1280dp wide so both step tiles have room for a name.
     */
    private class DockScene(pulsar: PulsarFeature, tv: Boolean = false) {
        var compositions = 0
        val scene = ImageComposeScene(1280, 200, Density(1f)) {
            CompositionLocalProvider(LocalTelevisionHardware provides tv) {
                OrpheusTheme {
                    DjTvBottomBar(
                        panels = bottomBarPanels(largeScreenPanels()),
                        isDocked = { compositions++; it == PulsarTab },
                        onToggle = {},
                        timerFeature = TimerViewModel.previewFeature(),
                        pulsarFeature = pulsar,
                        onTogglePlayback = {},
                    )
                }
            }
        }
    }

    /** The ◀ tile's end of the bar, clear of the centre dome and its name. */
    private fun ByteArray.previousTile(): List<Float> =
        Image.makeFromEncoded(this).toComposeImageBitmap().toPixelMap().let { p ->
            (0 until 320).flatMap { x -> (0 until p.height).map { y -> p[x, y].red + p[x, y].green + p[x, y].blue } }
        }

    // Playing or paused, a tile's long name scrolls; paused with no progress, nothing else moves.
    @Test
    fun scrollingStepTilesNeverRecomposeTheDock() {
        for (paused in listOf(false, true)) {
            val state = if (paused) "paused" else "playing"
            val nav = if (paused) longNavState.copy(progress = null) else longNavState
            val dock = DockScene(dockPulsar(paused, nav))
            try {
                (0L..1_200L step 16).forEach { dock.scene.frame(it) }
                val seen = dock.compositions
                val before = dock.scene.frame(1_216)
                (1_232L..2_000L step 16).forEach { dock.scene.frame(it) }
                assertTrue(dock.scene.hasInvalidations(), "the $state dock's step tiles should be scrolling")
                assertFalse(before.previousTile() == dock.scene.frame(2_016).previousTile(), "the $state ◀ tile never moved")
                assertEquals(seen, dock.compositions, "a scroll frame recomposed the $state dock")
            } finally {
                dock.scene.close()
            }
        }
    }

    @Test
    fun televisionHardwareDockAsksForNoFrames() {
        for (paused in listOf(false, true)) {
            val dock = DockScene(dockPulsar(paused, longNavState.copy(progress = null)), tv = true)
            try {
                (0L..2_000L step 16).forEach { dock.scene.frame(it) }
                assertFalse(dock.scene.hasInvalidations(), "a TV dock should never animate its step tiles (paused $paused)")
            } finally {
                dock.scene.close()
            }
        }
    }

    // Names that fit, paused with no progress so no zip: nothing on the dock asks for a frame.
    @Test
    fun aPausedDockWhoseNamesFitAsksForNoFrames() {
        val fits = VibeNavState(currentName = "Rust", previousName = "Dog", nextName = "Sky", progress = null)
        val dock = DockScene(dockPulsar(paused = true, nav = fits))
        try {
            assertTrue(dock.scene.settle(1_000) != null, "a paused dock whose names fit should not animate")
        } finally {
            dock.scene.close()
        }
    }
}

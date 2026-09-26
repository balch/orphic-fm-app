package org.balch.orpheus.djapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Frame budgets for the navigator's animated scenes, S0-S9 of the perf audit: JVM bytes allocated
 * and composable scopes recomposed per frame, on the scene's own thread. Scope counts are exact.
 * Byte budgets sit at twice the measured cost, or the audit's target where that is larger, as
 * headroom against JIT noise; tighter where twice would pass the cost before a fix (see the
 * companion). Each scene also prints its numbers, which land in
 * build/test-results/jvmTest/TEST-*FrameBudgetTest.xml.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*FrameBudgetTest*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameBudgetTest {
    private val longName = "Kaleidoscope Drift Sessions"

    private val navState = VibeNavState(
        "Rust Belt", "Dog House", "Stay Asleep", progress = 0.62f, positionMs = 74_400L, durationMs = 120_000L,
    )
    private val songPosition = SongPosition("Rust Belt", positionMs = 74_400L, durationMs = 120_000L)

    // Pre-built, so a frame's own setup allocates nothing.
    private val pulses = Array(16) { i ->
        MusicPulse(
            level = 0.4f + (i % 5) / 10f,
            trackLevels = FloatArray(8) { t -> if (t == i % 8 || t == (i + 3) % 8) 0.3f + t / 20f else 0f },
            beatPhase = (i % 4) / 4f,
            msPerBeat = 500f,
        )
    }
    private val effects = Array(16) { i ->
        VisualizationLiquidEffects(
            title = CenterPanelStyle(titleColor = Color.hsv(i * 360f / 16, 0.7f, 1f)),
        )
    }

    /** [count] back-to-back half-second beats from the song's start, their lead track cycling. */
    private fun storyOf(count: Int) = SongStory(
        List(count) { StoryBeat(0.3f + (it % 7) / 10f, FloatArray(8).also { e -> e[it % 8] = 1f }, it * 500L, 500L) },
        elapsedMs = count * 500L,
    )

    private fun pulsar(
        paused: Boolean,
        nav: VibeNavState = navState,
        story: StateFlow<SongStory> = MutableStateFlow(storyOf(200)),
        pulse: StateFlow<MusicPulse> = MutableStateFlow(pulses[0]),
    ): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
            override val songStoryFlow: StateFlow<SongStory> = story
            override val musicPulseFlow: StateFlow<MusicPulse> = pulse
            override val actions: PulsarPanelActions = base.actions
        }
    }

    private val panels = bottomBarPanels(largeScreenPanels())

    private fun report(line: String) = println("[frame-budget] $line")

    private fun scene(
        widthPx: Int,
        heightPx: Int,
        density: Float = 2f,
        tv: Boolean = false,
        scopes: ScopeCounter? = null,
        context: CoroutineContext = Dispatchers.Unconfined,
        // The master-mix scope a playing ring traces, as DjApp provides it.
        feed: ScopeFeed? = null,
        content: @Composable () -> Unit,
    ) = ImageComposeScene(widthPx, heightPx, Density(density), context) {
        if (scopes != null) ObserveScopes(scopes)
        CompositionLocalProvider(LocalTelevisionHardware provides tv, LocalScopeFeed provides feed) {
            OrpheusTheme { content() }
        }
    }

    // Pre-built, so publishing one allocates nothing: tones, a chord, and every fourth untriggered, as ScopeRingFrameBudgetTest's.
    private val windows = Array(16) { i -> if (i % 5 == 4) chordWindow(0.05f + i / 40f) else sineWindow(2f + i % 5, 0.02f + i / 20f) }

    /** A scope the app's 60 Hz poll feeds: a new window before every frame. */
    private class Scope(windows: Array<FloatArray>) {
        val frame = TestScopeFrame()
        val feed = ScopeFeed(frame) {}
        val newWindow = BeforeFrame { frame.publish(windows[it % windows.size], it % 4 != 3) }
    }

    /** The per-frame effects of a dynamic visualization: the harness's own provider is the only reader here. */
    @Composable
    private fun EffectsHost(effects: MutableState<VisualizationLiquidEffects>, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLiquidEffects provides effects.value, content = content)
    }

    private fun <T> FrameMeter.use(block: (FrameMeter) -> T): T = try { block(this) } finally { close() }

    private fun meter(scene: ImageComposeScene, scopes: ScopeCounter? = null) = FrameMeter(scene, scopes = scopes)

    private fun counted(scene: (ScopeCounter) -> ImageComposeScene): FrameMeter {
        val scopes = ScopeCounter()
        return FrameMeter(scene(scopes), scopes = scopes)
    }

    private fun effectsCycle(accent: MutableState<VisualizationLiquidEffects>) =
        BeforeFrame { accent.value = effects[it % effects.size] }

    // ==================== S1: the marquee ====================

    @Test
    fun theMarqueeScrollsWithoutGarbage() {
        val label = meter(
            scene(144, 40) {
                Box(Modifier.width(72.dp)) {
                    MarqueeLabel(longName, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            },
        ).use { it.bytesPerFrame(count = 240, warmup = 90) }
        // Into the first pass, which starts 1.2 s in and runs about 5 s.
        report("S1a marquee label: ${label - baseline} B/frame over S0 ($label raw)")
        assertTrue(label - baseline < MarqueeScrollBudget, "a scroll frame allocated ${label - baseline} B over S0")

        // A long name scrolls paused as it does playing; no progress, so paused there is no zip.
        fun transport(paused: Boolean) = meter(
            scene(132, 180) {
                Box(Modifier.width(66.dp)) {
                    VibeTransportItem(
                        name = longName, previousName = "Dog House", nextName = "Stay Asleep", progress = null,
                        paused = paused, onTogglePlayback = {}, onNext = {}, onPrevious = {}, nameStyle = barNameStyle,
                    )
                }
            },
        ).use { it.bytesPerFrame(count = 240, warmup = 90) }
        val playing = transport(paused = false)
        val paused = transport(paused = true)
        report("S1b bar transport, 66dp slot: ${playing - baseline} B/frame over S0 playing, ${paused - baseline} paused")
        assertTrue(playing - baseline < MarqueeScrollBudget, "the bar's scroll frame allocated ${playing - baseline} B over S0 playing")
        assertTrue(paused - baseline < MarqueeScrollBudget, "the bar's scroll frame allocated ${paused - baseline} B over S0 paused")
    }

    // ==================== S2 and S9: the ring and the dock's centre dome, playing ====================

    /** Names that fit their slots, so no marquee scrolls: S1 is the marquee's budget, this the dome's. */
    private val shortNames = navState.copy(currentName = "Rust", previousName = "Dog", nextName = "Sky")

    /** Real vibe names too long for the dome and the tiles of a 960dp dock, so all three scroll. */
    private val longNames = navState.copy(currentName = "Ouroboros Bloom", previousName = "Kaleidoscope Drift", nextName = "Vanished Skyline")

    /** The dock's bottom bar alone, [widthDp] wide, its centre dome playing on [pulsar] at its real floor size. */
    private fun domeScene(pulsar: PulsarFeature, scopes: ScopeCounter, widthDp: Int = 1280, feed: ScopeFeed? = null) =
        scene(widthDp, 200, density = 1f, scopes = scopes, feed = feed) {
            DjTvBottomBar(
                panels = panels, isDocked = { false }, onToggle = {},
                timerFeature = TimerViewModel.previewFeature(), pulsarFeature = pulsar, onTogglePlayback = {},
                domeRingSize = TvDockDomeRingSize,
            )
        }

    // The ring and the dome trace the scope, a new window every frame, as they do in the app.
    @Test
    fun theRingAndTheDomePlayWithoutRecomposing() {
        val ringScope = Scope(windows)
        counted { scopes ->
            scene(128, 128, scopes = scopes, feed = ringScope.feed) {
                VibeTransportRing(paused = false, progress = 0.62f, position = songPosition, pulse = { pulses[3] })
            }
        }.use { ring ->
            val bytes = ring.bytesPerFrame(before = ringScope.newWindow) - baseline
            val scopes = ring.scopesPerFrame(before = ringScope.newWindow)
            report("S2a ring tracing the scope: $bytes B/frame over S0, $scopes scopes/frame")
            assertEquals(0.0, scopes, "a playing ring recomposed")
            assertTrue(bytes < RingFrameBudget, "a ring frame allocated $bytes B over S0")
        }
        val domeScope = Scope(windows)
        counted { scopes -> domeScene(pulsar(paused = false, nav = shortNames), scopes, feed = domeScope.feed) }.use { dome ->
            dome.frames(4, domeScope.newWindow)
            val names = listOf(shortNames.currentName, shortNames.previousName!!, shortNames.nextName!!)
            assertEquals(emptyList(), scrollingNames(dome.scene, names), "sanity: a short name scrolls: ${nameWidths(dome.scene, names)}")
            val bytes = dome.bytesPerFrame(before = domeScope.newWindow) - baseline
            val scopes = dome.scopesPerFrame(before = domeScope.newWindow)
            report("S2b dock's centre dome tracing the scope: $bytes B/frame over S0, $scopes scopes/frame")
            assertEquals(0.0, scopes, "a playing dome recomposed its bottom bar")
            assertTrue(bytes < DomeScopeFrameBudget, "a dome frame allocated $bytes B over S0")
        }
    }

    private fun nameNodes(scene: ImageComposeScene, name: String): List<SemanticsNode> {
        fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
        return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
            .filter { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name } }
    }

    /**
     * Which of [names] are scrolling: a live marquee measures its text whole and reports only its
     * lane, so the text node inside is wider than the label it sits in.
     */
    private fun scrollingNames(scene: ImageComposeScene, names: List<String>): List<String> =
        names.filter { name -> nameNodes(scene, name).any { it.size.width > it.layoutInfo.width + 1 } }

    private fun nameWidths(scene: ImageComposeScene, names: List<String>): String = names.joinToString { name ->
        "$name: " + nameNodes(scene, name).joinToString { "text ${it.size.width} in ${it.layoutInfo.width}" }
    }

    // The dome and both tiles with real names too long for them, so all three marquees scroll.
    @Test
    fun longNamesScrollInTheDomeAndTilesWithoutRecomposing() {
        val names = listOf(longNames.currentName, longNames.previousName!!, longNames.nextName!!)
        counted { scopes -> domeScene(pulsar(paused = false, nav = longNames), scopes, widthDp = 960) }.use { dome ->
            dome.frames(4)
            assertEquals(names, scrollingNames(dome.scene, names), "sanity: not every name scrolls: ${nameWidths(dome.scene, names)}")
            val bytes = dome.bytesPerFrame() - baseline
            val scopes = dome.scopesPerFrame()
            report("S2c dock's centre dome and tiles, long names scrolling: $bytes B/frame over S0, $scopes scopes/frame")
            assertEquals(0.0, scopes, "scrolling names recomposed the bottom bar")
            assertTrue(bytes < LongNameDomeBudget, "a frame with the names scrolling allocated $bytes B over S0")
        }
    }

    // Over the scope, a new window every frame either way, so only the pulse differs.
    @Test
    fun aNewPulseEveryFrameCostsTheRingNothing() {
        fun phoneBar(changing: Boolean): Pair<Long, Double> {
            val pulse = MutableStateFlow(pulses[0])
            val scope = Scope(windows)
            val step = BeforeFrame {
                scope.newWindow.run(it)
                if (changing) pulse.value = pulses[it % pulses.size]
            }
            return navMeter(DjLayout.Portrait, 360, 780, pulsar(paused = false, pulse = pulse), feed = scope.feed).use {
                it.bytesPerFrame(before = step) to it.scopesPerFrame(before = step)
            }
        }
        val (steady, _) = phoneBar(changing = false)
        val (changing, scopes) = phoneBar(changing = true)
        report("S9a phone bar ring: ${changing - baseline} B/frame over S0 with a new pulse every frame, ${steady - baseline} with a constant one, $scopes scopes/frame")
        assertEquals(0.0, scopes, "a new pulse recomposed the phone bar")
        assertTrue(changing - steady < PulseReadBudget, "a new pulse every frame cost ${changing - steady} B/frame")

        // The dock's centre dome reads the pulse by value too, as the phone bar does.
        fun dome(changing: Boolean): Pair<Long, Double> {
            val pulse = MutableStateFlow(pulses[0])
            val scope = Scope(windows)
            val step = BeforeFrame {
                scope.newWindow.run(it)
                if (changing) pulse.value = pulses[it % pulses.size]
            }
            return counted { counter -> domeScene(pulsar(paused = false, nav = shortNames, pulse = pulse), counter, feed = scope.feed) }
                .use { it.bytesPerFrame(before = step) to it.scopesPerFrame(before = step) }
        }
        val (domeSteady, _) = dome(changing = false)
        val (domeChanging, domeScopes) = dome(changing = true)
        report("S9b dock's centre dome: ${domeChanging - baseline} B/frame over S0 with a new pulse every frame, ${domeSteady - baseline} with a constant one, $domeScopes scopes/frame")
        assertEquals(0.0, domeScopes, "a new pulse recomposed the bottom bar")
        assertTrue(domeChanging - domeSteady < PulseReadBudget, "a new pulse every frame cost the dome ${domeChanging - domeSteady} B/frame")
    }

    // ==================== S3: the paused zip ====================

    /** A cycle to warm up, then three (1.6 s passes, 0.8 s rests): bytes per pass frame, and that the zip drew. */
    private fun FrameMeter.zipBytes(what: String): Long? {
        frames(150)
        val bytes = bytesPerRequestedFrame(450)
        // Drawn, not only clocked: over a cycle the ring's pixels move, so ZipGradient's sweep is in the bytes.
        val cycle = List(150) { _ -> image() }
        assertTrue(cycle.zipWithNext().any { (a, b) -> !a.contentEquals(b) }, "the $what zip never drew")
        return bytes
    }

    @Test
    fun theZipsPassWithLittleGarbage() {
        // Opened paused under the scope: nothing played yet, so the zip lights the plain arc.
        val scheduler = TestCoroutineScheduler()
        val ring = FrameMeter(
            scene(128, 128, context = UnconfinedTestDispatcher(scheduler), feed = Scope(windows).feed) {
                VibeTransportRing(paused = true, progress = 0.62f, position = songPosition)
            },
            scheduler = scheduler,
        ).use { it.zipBytes("ring's") }
        val ringZip = checkNotNull(ring) { "the ring never zipped" } - baseline
        report("S3a ring zip, opened paused: $ringZip B/pass frame over S0")
        assertTrue(ringZip < RingZipBudget, "a ring zip frame allocated $ringZip B over S0")

        // Played on the scope for a second, then paused: the zip lights the held trace, whose path each pass frame rebuilds.
        val heldScheduler = TestCoroutineScheduler()
        val scope = Scope(windows)
        val paused = mutableStateOf(false)
        val held = FrameMeter(
            scene(128, 128, context = UnconfinedTestDispatcher(heldScheduler), feed = scope.feed) {
                VibeTransportRing(paused = paused.value, progress = 0.62f, position = songPosition, pulse = { pulses[3] })
            },
            scheduler = heldScheduler,
        ).use {
            it.frames(60, scope.newWindow)
            paused.value = true
            it.zipBytes("held trace's")
        }
        val heldZip = checkNotNull(held) { "the held trace never zipped" } - baseline
        report("S3c ring zip over a held scope trace: $heldZip B/pass frame over S0")
        assertTrue(heldZip < HeldTraceZipBudget, "a zip frame over the held trace allocated $heldZip B over S0")

        val bandScheduler = TestCoroutineScheduler()
        val band = FrameMeter(
            scene(2560, 80, context = UnconfinedTestDispatcher(bandScheduler)) { DockSongBand(pulsar(paused = true)) },
            scheduler = bandScheduler,
        ).use {
            it.frames(150)
            it.bytesPerRequestedFrame(450)
        }
        val bandZip = checkNotNull(band) { "the band never zipped" } - baseline
        report("S3b band zip: $bandZip B/pass frame over S0")
        assertTrue(bandZip < BandZipBudget, "a band zip frame allocated $bandZip B over S0")
    }

    // ==================== S4: the song band, and the dock under a dynamic visualization ====================

    private fun bandMeter(tv: Boolean, story: MutableStateFlow<SongStory>): FrameMeter {
        val pulsar = pulsar(paused = false, story = story)
        return counted { scopes -> scene(2560, 80, tv = tv, scopes = scopes) { DockSongBand(pulsar) } }
    }

    private fun steadyBand(tv: Boolean): Long = bandMeter(tv, MutableStateFlow(storyOf(200))).use {
        val bytes = it.bytesPerFrame()
        val scopes = it.scopesPerFrame()
        report("S4a band steady (${where(tv)}): $bytes B/frame, $scopes scopes/frame")
        assertEquals(0.0, scopes, "a steady band recomposed (${where(tv)})")
        bytes
    }

    private fun where(tv: Boolean) = if (tv) "tv" else "off tv"

    @Test
    fun theBandPlaysAndTakesNewBeatsWithoutRecomposing() {
        for (tv in listOf(false, true)) {
            val steady = steadyBand(tv)
            if (tv) {
                // Nothing animates on TV hardware: this is the idle render alone.
                assertTrue(steady < TvBandIdleBudget, "an idle TV band frame allocated $steady B")
            } else {
                assertTrue(steady - baseline < BandFrameBudget, "a band frame allocated ${steady - baseline} B over S0")
            }
            val stories = Array(20) { storyOf(200 + it) }
            val story = MutableStateFlow(stories[0])
            val nextStory = BeforeFrame { if (it % 30 == 0) story.value = stories[(it / 30) % stories.size] }
            bandMeter(tv, story).use {
                val bytes = it.bytesPerFrame(before = nextStory)
                val scopes = it.scopesPerFrame(before = nextStory)
                report("S4b band, a new beat every 30 frames (${where(tv)}): $bytes B/frame, ${bytes - steady} over steady, $scopes scopes/frame")
                assertEquals(0.0, scopes, "a new beat recomposed the band (${where(tv)})")
                val budget = if (tv) TvNewBeatBudget else NewBeatBudget
                assertTrue(bytes - steady < budget, "new beats cost the band ${bytes - steady} B/frame (${where(tv)})")
                if (tv) {
                    // The playhead stands still, so no bar settles: the static band asks for no frame at all.
                    val requested = it.bytesPerRequestedFrame(120, before = nextStory)
                    report("S4b TV band, frames asked for while nothing settles: ${requested ?: "none"}")
                    assertNull(requested, "a new beat inside one bar redrew the TV band")
                }
            }
        }
    }

    /**
     * The dock under the top bar as the chrome stacks it: the band, and the bottom bar with DJ docked
     * and, off TV hardware, in its glass over a liquefiable backdrop as DjApp provides one.
     */
    private fun dockMeter(tv: Boolean, accent: MutableState<VisualizationLiquidEffects>?): FrameMeter {
        val pulsar = pulsar(paused = false)
        val dock: @Composable () -> Unit = {
            val liquid = rememberLiquidState()
            CompositionLocalProvider(LocalLiquidState provides liquid) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF203050)).liquefiable(liquid))
                    Column {
                        DockSongBand(pulsar)
                        DjTvBottomBar(
                            panels = panels, isDocked = { it == DjTab }, onToggle = {},
                            timerFeature = TimerViewModel.previewFeature(), pulsarFeature = pulsar, onTogglePlayback = {},
                            glass = !tv,
                            domeRingSize = TvDockDomeRingSize,
                        )
                    }
                }
            }
        }
        return counted { scopes ->
            scene(2560, 440, tv = tv, scopes = scopes) {
                if (accent != null) EffectsHost(accent, dock) else dock()
            }
        }
    }

    /** The harness's own provider, around nothing: what S4c and S5 pay before the bar or rail does anything. */
    private fun providerControl(): Pair<Long, Double> {
        val accent = mutableStateOf(effects[0])
        val cycle = effectsCycle(accent)
        return counted { scopes -> scene(2560, 440, scopes = scopes) { EffectsHost(accent) { Box(Modifier.size(1.dp)) } } }
            .use { it.bytesPerFrame(before = cycle) to it.scopesPerFrame(before = cycle) }
    }

    @Test
    fun anAccentEveryFrameRebuildsNoShape() {
        val (controlBytes, controlScopes) = providerControl()
        report("S4c control, the provider alone: $controlBytes B/frame, $controlScopes scopes/frame")
        for (tv in listOf(false, true)) {
            val steady = dockMeter(tv, null).use { it.bytesPerFrame() }
            val accent = mutableStateOf(effects[0])
            val cycle = effectsCycle(accent)
            dockMeter(tv, accent).use {
                val bytes = it.bytesPerFrame(before = cycle)
                val scopes = it.scopesPerFrame(before = cycle) - controlScopes
                report("S4c dock, accent every frame (${where(tv)}): $bytes B/frame, ${bytes - steady} over steady, $scopes scopes/frame over the control")
                // The glass alone: the band, the docked item, the border and the dome read it in draw.
                val expected = if (tv) 0.0 else DockAccentScopes
                assertEquals(expected, scopes, "an accent change recomposed $scopes scopes in the dock (${where(tv)})")
                val budget = if (tv) TvDockAccentBudget else DockAccentBudget
                assertTrue(bytes - steady < budget, "an accent change cost the dock ${bytes - steady} B/frame (${where(tv)})")
            }
        }
    }

    // ==================== S5: the rail under a dynamic visualization ====================

    private fun navMeter(
        layout: DjLayout,
        widthDp: Int,
        heightDp: Int,
        pulsar: PulsarFeature,
        timer: TimerFeature = TimerViewModel.previewFeature(),
        effectsState: MutableState<VisualizationLiquidEffects>? = null,
        feed: ScopeFeed? = null,
    ): FrameMeter {
        val nav: @Composable () -> Unit = {
            val liquid = rememberLiquidState()
            CompositionLocalProvider(LocalLiquidState provides liquid) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF203050)).liquefiable(liquid))
                    DjAppNavScaffold(
                        isSelected = { it == DjTab }, onItemClick = {}, layout = layout, pulsarFeature = pulsar,
                        timerFeature = timer, onTogglePlayback = {}, modifier = Modifier.fillMaxSize(),
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        return counted { scopes ->
            scene(widthDp * 2, heightDp * 2, scopes = scopes, feed = feed) {
                if (effectsState != null) EffectsHost(effectsState, nav) else nav()
            }
        }
    }

    // The glass must read the new effects, so its own scope recomposes: the rail and the transport never do.
    @Test
    fun theRailsGlassAloneFollowsTheAccent() {
        val (controlBytes, controlScopes) = providerControl()
        val still = pulsar(paused = true, nav = navState.copy(progress = null))
        val accent = mutableStateOf(effects[0])
        val cycle = effectsCycle(accent)
        navMeter(DjLayout.Landscape, 800, 360, still, effectsState = accent).use {
            val bytes = it.bytesPerFrame(before = cycle) - controlBytes
            val scopes = it.scopesPerFrame(before = cycle) - controlScopes
            report("S5 rail, accent every frame: $bytes B/frame over the control, $scopes scopes/frame over the control")
            assertEquals(RailAccentScopes, scopes, "an accent change recomposed more of the rail than its glass")
            assertTrue(bytes < RailAccentBudget, "an accent change cost the rail $bytes B/frame")
            // Skipping is not freezing: the glass still redraws in each new accent.
            assertFalse(it.image(cycle).contentEquals(it.image(cycle)), "the rail's glass stopped following the accent")
        }
    }

    // ==================== S6: a running timer ====================

    @Test
    fun aRunningTimerRecomposesNothing() {
        val timer = TimerViewModel.previewFeature(TimerUiState(remainingTime = 12.minutes, status = TimerStatus.RUNNING))
        val still = pulsar(paused = true, nav = navState.copy(progress = null))
        navMeter(DjLayout.Portrait, 360, 780, still, timer).use {
            val bytes = it.bytesPerFrame(count = 120) - baseline
            val scopes = it.scopesPerFrame()
            report("S6a phone bar, timer running: $bytes B/frame over S0, $scopes scopes/frame")
            assertEquals(0.0, scopes, "the countdown's pulse recomposed the phone bar")
            assertTrue(bytes < PhoneCountdownBudget, "a countdown frame allocated $bytes B over S0")
            assertStillPulses(it, "phone bar")
        }
        counted { scopes ->
            scene(2560, 440, scopes = scopes) {
                DjTvBottomBar(
                    panels = panels, isDocked = { it == PulsarTab }, onToggle = {}, timerFeature = timer, pulsarFeature = still,
                    onTogglePlayback = {}, domeRingSize = TvDockDomeRingSize,
                )
            }
        }.use {
            val bytes = it.bytesPerFrame(count = 120) - baseline
            val scopes = it.scopesPerFrame()
            report("S6b dock, timer running: $bytes B/frame over S0, $scopes scopes/frame")
            assertEquals(0.0, scopes, "the countdown's pulse recomposed the dock")
            assertTrue(bytes < DockCountdownBudget, "a countdown frame allocated $bytes B over S0")
            assertStillPulses(it, "dock")
        }
    }

    /** Recomposing nothing is not standing still: the countdown keeps asking for frames and its pixels move over 500 ms. */
    private fun assertStillPulses(meter: FrameMeter, where: String) {
        assertTrue(meter.scene.hasInvalidations(), "the $where's countdown stopped asking for frames")
        val first = meter.image()
        // Sampled every 80 ms: a pulse reversing between two samples could draw them alike.
        val moved = (1..6).any { meter.frames(4); !first.contentEquals(meter.image()) }
        assertTrue(moved, "the $where's countdown never moved in 500 ms")
    }

    // ==================== S7: a swipe ====================

    /** One swipe's cost: bytes per move, the scopes each move recomposed, and the release's. */
    private class Swipe(val bytesPerMove: Long, val perMove: IntArray, val release: Int)

    /** Presses at [start] and swipes right a move a frame, past the peek and the commit, then releases. */
    private fun FrameMeter.swipe(start: Offset): Swipe {
        val counter = checkNotNull(scopes)
        scene.sendPointerEvent(PointerEventType.Press, start)
        frame()
        val perMove = IntArray(30)
        var x = start.x
        var bytes = 0L
        repeat(30) { i ->
            // 1 or 2 dp a move, at density 2: past the 16dp peek and the 32dp commit.
            x += if (i % 2 == 0) 2f else 4f
            val seen = counter.entered
            val before = FrameMeter.allocatedBytes()
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, start.y))
            frame()
            bytes += FrameMeter.allocatedBytes() - before
            perMove[i] = counter.entered - seen
        }
        val seen = counter.entered
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, start.y))
        frame()
        return Swipe(bytes / 30, perMove, counter.entered - seen)
    }

    @Test
    fun aSwipeRecomposesOnlyAtItsThresholdAndRelease() {
        val name = "Space & Drift"
        val still = pulsar(paused = true, nav = VibeNavState(name, "Dog House", "Stay Asleep", progress = null))
        navMeter(DjLayout.Portrait, 360, 780, still).use { bar ->
            bar.frames(4)
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            val transport = bar.scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.first { n ->
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(", $name") }
            }
            val start = Offset(transport.positionInRoot.x + transport.size.width / 2f, transport.positionInRoot.y + 70f)
            // A scene's first swipe runs code no earlier test may have warmed, at twice the bytes or
            // more: swipes of its own come first, and the dome's roll settles after each.
            val warm = List(SwipeWarmups) { bar.swipe(start).also { bar.frames(60) }.bytesPerMove }
            val swipe = bar.swipe(start)
            val perMove = swipe.perMove
            report("S7 swipe: ${swipe.bytesPerMove} B/move, scopes per move ${perMove.joinToString()}, release ${swipe.release} (warm-ups ${warm.joinToString()})")
            assertEquals(1, perMove.count { it > 0 }, "a swipe recomposed on moves other than the peek threshold: ${perMove.joinToString()}")
            assertTrue(swipe.release > 0, "the release never recomposed the peek back to the name, so the counter is blind")
            assertTrue(swipe.bytesPerMove < SwipeMoveBudget, "a swipe move allocated ${swipe.bytesPerMove} B")
        }
    }

    companion object {
        /** S0: a bare frame loop and one redrawn Canvas, what every scene pays to render a frame at all. */
        private val baseline: Long by lazy {
            val value = mutableFloatStateOf(0f)
            FrameMeter(
                ImageComposeScene(128, 128, Density(2f)) {
                    LaunchedEffect(Unit) { while (true) withFrameNanos { } }
                    Canvas(Modifier.size(64.dp)) { drawCircle(Color.White, 8f + value.floatValue) }
                },
            ).let { meter ->
                try {
                    meter.bytesPerFrame { value.floatValue = (it % 16).toFloat() }
                } finally {
                    meter.close()
                }
            }.also { println("[frame-budget] S0 baseline: $it B/frame") }
        }

        // Budgets: twice the cost measured, or the audit's target where larger. Where twice could not
        // tell a fix from its before (S2, S3, S4b, S7), tighter, below the before. Every "measured"
        // below is from one full-suite run, 2026-09-25; S2a, S2b, S3a and S3c, now over the scope as the
        // app draws them, from three cold filtered runs of this file the same day.

        /**
         * S1, a marquee scroll frame over S0: measured 202 B, and the bar's transport 177 B playing and
         * 183 B paused (the fades' brush once cost about 1.2 KB).
         */
        private const val MarqueeScrollBudget = 390L

        /**
         * S2a, a playing ring frame over S0, tracing a new scope window each frame: measured 277-313 B
         * (bimodal across runs); 1.5x the lower, below the 529 B before the perf fixes.
         */
        private const val RingFrameBudget = 420L

        /**
         * S2b, the dock's playing centre dome, tracing the scope as S2a's ring does: measured 311-313 B, and
         * 277 B in some runs, the ring's bimodality; S2a's budget, below the same 529 B.
         */
        private const val DomeScopeFrameBudget = 420L

        /** S2c, the playing dome with its own and both tiles' names scrolling, over S0: measured 2.6 KB. */
        private const val LongNameDomeBudget = 5_230L

        /** S9, a new pulse every frame against a constant one, over the scope: measured 0; 150 B is JIT noise (collecting cost 500 B). */
        private const val PulseReadBudget = 150L

        /** S3a, a ring opened paused under the scope, its zip frame over S0: measured 1359-1367 B; about 1.5x, below the 2367 B before the perf fixes. */
        private const val RingZipBudget = 1_990L

        /**
         * S3c, the zip over a held scope trace, its path rebuilt each pass frame: measured 1564-1568 B;
         * 1.5x, below the 2367 B a zip frame cost before the perf fixes.
         */
        private const val HeldTraceZipBudget = 2_350L

        /** S3, a paused band's zip frame over S0: measured 1118 B (every bar redrawn once cost 4.7 KB). */
        private const val BandZipBudget = 2_210L

        /** S4a, a playing band frame over S0: measured 305 B (every bar redrawn once cost 4.6 KB). */
        private const val BandFrameBudget = 660L

        /** S4a on TV hardware, an idle band's render: the budget the whole idle TV dock had (measured 344 B). */
        private const val TvBandIdleBudget = 690L

        /** S4b, a new beat every 30 frames over the steady band, off TV: measured 144 B (434 B before the perf fixes). */
        private const val NewBeatBudget = 300L

        /** S4b on TV hardware, where the band is static: measured 16 B; 150 B is JIT noise (329 B before the dock redesign). */
        private const val TvNewBeatBudget = 150L

        /**
         * S4c, the accent every frame over the steady dock (band and bottom bar, DJ docked, in glass),
         * off TV: measured 5.5 KB, with the relay and the docked item recomposing 8.8 KB.
         */
        private const val DockAccentBudget = 11_120L

        /** S4c on TV hardware, no glass: measured 4.6 KB (8.5 KB recomposing). */
        private const val TvDockAccentBudget = 9_270L

        /** S4c, scopes over the provider control off TV: the glass alone, reading the effects. TV has none. */
        private const val DockAccentScopes = 1.0

        /** S5, the rail's glass: its own scope and NavigationRail's, which skips. */
        private const val RailAccentScopes = 2.0

        /** S5, the accent every frame over the provider control: measured 2.2 KB (the rail recomposing cost 7.3 KB). */
        private const val RailAccentBudget = 4_280L

        /** S6, a running countdown frame over S0 in the phone bar: measured 1088 B. */
        private const val PhoneCountdownBudget = 1_990L

        /** S6, the same in the dock, whose layer the digits redraw: measured 2.2 KB (2.9 KB before the dock redesign). */
        private const val DockCountdownBudget = 4_360L

        /**
         * S7, one swipe move, pointer handling included: measured 18.3 KB, cold or warm. 1.3x, below the
         * 26 KB a move cost when each recomposed. Up from 15.7 KB: a paused name now wears the marquee.
         */
        private const val SwipeMoveBudget = 23_800L

        /** S7's own swipes before the measured one: the first cost 44.5 KB a move cold and 37 KB in the suite, the second 18.3 KB. */
        private const val SwipeWarmups = 2
    }
}

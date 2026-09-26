package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressWaveTest {
    // Pause keeps the wave's last shape: the amplitude stays where pause landed, even mid-rise.
    @Test
    fun aPausedWaveHoldsItsAmplitude() {
        assertEquals(1f, waveAmplitudeTarget(playing = false, televisionHardware = false, held = 1f))
        assertEquals(0.4f, waveAmplitudeTarget(playing = false, televisionHardware = false, held = 0.4f))
    }

    // Never played (opened paused): nothing to hold, so it stays flat until play.
    @Test
    fun aWaveThatNeverPlayedStaysFlatWhilePaused() =
        assertEquals(0f, waveAmplitudeTarget(playing = false, televisionHardware = false, held = 0f))

    @Test
    fun theWaveIsFlatOnTelevisionHardware() = assertEquals(0f, waveAmplitudeTarget(playing = true, televisionHardware = true, held = 1f))

    @Test
    fun thePausedTelevisionWaveIsFlat() = assertEquals(0f, waveAmplitudeTarget(playing = false, televisionHardware = true, held = 1f))

    @Test
    fun aPlayingWaveRisesToFullFromWhereverItWas() {
        assertEquals(1f, waveAmplitudeTarget(playing = true, televisionHardware = false, held = 0f))
        assertEquals(1f, waveAmplitudeTarget(playing = true, televisionHardware = false, held = 0.4f))
    }

    // Scene checks: render(nanoTime) steps the frame clock, so motion and idleness are observable.
    // 128px at 2x holds the whole 64dp bar ring, so the pixel checks see the ring, not the dome.
    // A ring over a scope that publishes a sine, as the app's rings draw the master mix's.
    private fun ringScene(
        paused: () -> Boolean,
        tv: Boolean = false,
        progress: Float? = 0.62f,
        context: CoroutineContext = Dispatchers.Unconfined,
        scope: TestScopeFrame = TestScopeFrame().apply { publish(sineWindow(cycles = 3f, amplitude = 0.5f), triggered = true) },
    ) = ImageComposeScene(128, 128, Density(2f), context) {
        CompositionLocalProvider(LocalTelevisionHardware provides tv, LocalScopeFeed provides ScopeFeed(scope) {}) {
            VibeTransportRing(paused = paused(), progress = progress)
        }
    }

    private fun ImageComposeScene.frame(ms: Long): ByteArray = render(ms * 1_000_000).encodeToData()!!.bytes

    @Test
    fun aPlayingRingTracesTheScope() {
        val scope = TestScopeFrame().apply { publish(sineWindow(cycles = 3f, amplitude = 0.5f), triggered = true) }
        val scene = ringScene(paused = { false }, scope = scope)
        try {
            val before = scene.frame(1_000)
            scope.publish(sineWindow(cycles = 5f, amplitude = 0.8f), triggered = true)
            assertFalse(before.contentEquals(scene.frame(1_300)), "a new window didn't redraw the ring")
            assertTrue(scene.hasInvalidations())
        } finally {
            scene.close()
        }
    }

    // Pause keeps the last shape: the frame the pause lands on stays drawn, traced, until the zip lights it.
    @Test
    fun aPausedRingHoldsItsLastShapeThenZips() {
        var paused by mutableStateOf(false)
        val scene = ringScene(paused = { paused })
        val flat = ringScene(paused = { true }, tv = true)
        try {
            (1_000L..1_300L step 16).forEach { scene.frame(it) }
            paused = true
            Snapshot.sendApplyNotifications()
            // The frame loop's last write lands with the pause; the zip's first lit frame is two later.
            val held = scene.frame(1_316)
            assertTrue(held.contentEquals(scene.frame(1_332)), "the paused ring kept moving")
            assertFalse(held.contentEquals(flat.frame(1_000)), "the paused ring lay flat")
            assertTrue(scene.hasInvalidations(), "a paused ring should be zipping")
            assertFalse(scene.frame(2_100).contentEquals(scene.frame(2_500)), "the zip never moved along the ring")
        } finally {
            scene.close()
            flat.close()
        }
    }

    // Amplitude, phase, loudness and track colours all hold where the pause landed; resuming carries on from there.
    @Test
    fun aPausedWaveKeepsItsShapeAndResumesWithoutAJump() {
        var playing by mutableStateOf(true)
        lateinit var wave: ProgressWave
        val loud = MusicPulse(level = 1f, trackLevels = FloatArray(8).also { it[0] = 1f }, beatPhase = 0.2f, msPerBeat = 500f)
        val scene = ImageComposeScene(10, 10) { wave = rememberProgressWave(playing = playing, pulse = { loud }, zip = true) }
        try {
            (1_000L..1_300L step 16).forEach { scene.render(it * 1_000_000) }
            playing = false
            Snapshot.sendApplyNotifications()
            scene.render(1_316L * 1_000_000)
            val phase = wave.phase
            val level = wave.level
            val tracks = wave.trackLevels.copyOf()
            assertTrue(level > 0.5f, "the wave never heard the loud pulse: $level")
            (1_332L..2_300L step 16).forEach { scene.render(it * 1_000_000) }
            assertEquals(1f, wave.amplitude, "the paused wave lost its height")
            assertEquals(phase, wave.phase, "the paused wave kept travelling")
            assertEquals(level, wave.level, "the paused wave's loudness moved")
            assertTrue(tracks.contentEquals(wave.trackLevels), "the paused wave's colour moved")
            playing = true
            Snapshot.sendApplyNotifications()
            scene.render(2_316L * 1_000_000)
            scene.render(2_332L * 1_000_000)
            assertEquals(1f, wave.amplitude, "the wave dipped on resume")
            val moved = abs((wave.phase - phase + PI.toFloat()).mod(2 * PI.toFloat()) - PI.toFloat())
            assertTrue(moved < 0.5f, "resume jumped the travel by $moved rad")
        } finally {
            scene.close()
        }
    }

    // Progress runs on at play time between the tracker's updates, without recomposing, and stops on pause.
    @Test
    fun theProgressRunsOnBetweenUpdatesAndFreezesOnPause() {
        var playing by mutableStateOf(true)
        var position by mutableStateOf(SongPosition("Rust Belt", positionMs = 60_000L, durationMs = 120_000L))
        var runs = 0
        lateinit var smooth: SmoothProgress
        val scene = ImageComposeScene(10, 10) {
            runs++
            val wave = rememberProgressWave(playing = playing)
            smooth = rememberSmoothProgress(wave) { position }
        }
        var now = 1_000L
        fun frames(count: Int) = repeat(count) {
            now += 16
            scene.render(now * 1_000_000)
        }
        try {
            scene.render(now * 1_000_000)
            frames(1)
            val start = smooth.fractionOr(0.5f)
            val seen = runs
            frames(62)
            assertEquals(start + 62 * 16f / 120_000f, smooth.fractionOr(0.5f), 1e-5f)
            assertEquals(seen, runs, "the running progress recomposed")
            playing = false
            Snapshot.sendApplyNotifications()
            frames(1)
            val held = smooth.fractionOr(0.5f)
            frames(60)
            assertEquals(held, smooth.fractionOr(0.5f), "the paused progress moved")
            // A new song lands even while paused.
            position = SongPosition("Dog House", positionMs = 0L, durationMs = 90_000L)
            Snapshot.sendApplyNotifications()
            frames(1)
            assertEquals(0f, smooth.fractionOr(0f))
        } finally {
            scene.close()
        }
    }

    // TV hardware has no frame loop to run the clock: it draws the tracker's own fraction.
    @Test
    fun televisionHardwareDrawsTheTrackersProgress() {
        lateinit var smooth: SmoothProgress
        val scene = ImageComposeScene(10, 10) {
            CompositionLocalProvider(LocalTelevisionHardware provides true) {
                val wave = rememberProgressWave(playing = true)
                smooth = rememberSmoothProgress(wave) { SongPosition("Rust Belt", 60_000L, 120_000L) }
            }
        }
        try {
            (1_000L..2_000L step 100).forEach { scene.render(it * 1_000_000) }
            assertEquals(0.5f, smooth.fractionOr(0.5f))
        } finally {
            scene.close()
        }
    }

    // The rest is a delay, on a test scheduler here, so it passes only when the test says so.
    @Test
    fun theZipRestsBetweenPassesWithoutFrames() {
        val scheduler = TestCoroutineScheduler()
        val scene = ringScene(paused = { true }, context = UnconfinedTestDispatcher(scheduler))
        try {
            // Paused from the start, so the zip starts on the first frame.
            (1_000L..4_000L step 50).forEach { scene.frame(it) }
            assertFalse(scene.hasInvalidations(), "the zip asked for frames through its rest")
            scheduler.advanceTimeBy(ZipRestMillis.toLong())
            scheduler.runCurrent()
            assertTrue(scene.hasInvalidations(), "the zip never came back after its rest")
        } finally {
            scene.close()
        }
    }

    @Test
    fun aPausedTelevisionRingAsksForNoFrames() {
        val scene = ringScene(paused = { true }, tv = true)
        try {
            val first = scene.frame(1_000)
            assertFalse(scene.hasInvalidations(), "a paused TV ring asks for frames")
            assertTrue(first.contentEquals(scene.frame(1_800)))
        } finally {
            scene.close()
        }
    }

    @Test
    fun aPausedRingWithoutProgressAsksForNoFrames() {
        val scene = ringScene(paused = { true }, progress = null)
        try {
            scene.frame(1_000)
            scene.frame(1_500)
            assertFalse(scene.hasInvalidations(), "a paused ring with nothing to zip asks for frames")
        } finally {
            scene.close()
        }
    }

    @Test
    fun televisionHardwareNeverAnimates() {
        val scene = ringScene(paused = { false }, tv = true)
        try {
            val first = scene.frame(1_000)
            assertFalse(scene.hasInvalidations(), "a TV ring asks for frames")
            assertTrue(first.contentEquals(scene.frame(1_300)))
        } finally {
            scene.close()
        }
    }

    // A counting draw in a layer around the ring stands in for the bar or rail around it.
    @Test
    fun aPulseRedrawsOnlyTheRing() {
        var pulse by mutableStateOf(MusicPulse.SILENT)
        var surroundDraws = 0
        // The same window each frame, as a held note would: each one redraws the ring in the pulse's colours.
        val scope = TestScopeFrame()
        val window = sineWindow(cycles = 3f, amplitude = 0.5f)
        val scene = ImageComposeScene(136, 136, Density(2f)) {
            CompositionLocalProvider(LocalScopeFeed provides ScopeFeed(scope) {}) {
                Box(Modifier.graphicsLayer().drawBehind { surroundDraws++ }) {
                    VibeTransportRing(paused = false, progress = 0.62f, pulse = { pulse })
                }
            }
        }
        fun step(ms: Long): ByteArray = scope.publish(window, triggered = true).let { scene.frame(ms) }
        try {
            (1_000L..1_200L step 16).forEach { step(it) }
            val quiet = step(1_216)
            val baseline = surroundDraws
            pulse = MusicPulse(level = 1f, trackLevels = FloatArray(8).also { it[0] = 1f }, beatPhase = 0.2f, msPerBeat = 500f)
            (1_232L..1_400L step 16).forEach { step(it) }
            assertFalse(quiet.contentEquals(step(1_416)), "a loud red pulse never showed on the ring")
            assertEquals(baseline, surroundDraws, "a pulse re-recorded the layer around the ring")
        } finally {
            scene.close()
        }
    }

    // With a duration, so the bar and scaffold scenes draw the smoothed progress.
    private val navState = VibeNavState(
        "Rust Belt", "Dog House", "Stay Asleep", progress = 0.62f, positionMs = 74_400L, durationMs = 120_000L,
    )

    /** A bar scene whose [compositions] count runs of DjTvBottomBar's own body (isDocked is called there). */
    private class BarScene(pulsar: PulsarFeature, tv: Boolean) {
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

    private fun pausedPulsar(
        state: StateFlow<PulsarUiState>? = null,
        story: StateFlow<SongStory> = MutableStateFlow(SongStory.EMPTY),
    ): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = state ?: base.stateFlow
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(navState)
            override val songStoryFlow: StateFlow<SongStory> = story
        }
    }

    // The bar derives only play/pause from Pulsar state, so an unrelated field must not touch it.
    @Test
    fun anUnrelatedPulsarChangeLeavesAPausedTelevisionBarIdle() {
        val state = MutableStateFlow(PulsarViewModel.previewFeature().stateFlow.value)
        val bar = BarScene(pausedPulsar(state), tv = true)
        try {
            bar.scene.frame(1_000)
            bar.scene.frame(1_100)
            assertFalse(bar.scene.hasInvalidations(), "the paused TV bar was not idle to begin with")
            state.value = state.value.copy(energy = 0.9f)
            Snapshot.sendApplyNotifications()
            assertFalse(bar.scene.hasInvalidations(), "an energy change invalidated the bar")
        } finally {
            bar.scene.close()
        }
    }

    /** [count] back-to-back half-second beats from the song's start, led by [lead]. */
    private fun storyOf(count: Int, lead: Int = 0) = SongStory(
        List(count) { StoryBeat(0.6f, FloatArray(8).also { e -> e[lead] = 1f }, positionMs = it * 500L, durationMs = 500L) },
        elapsedMs = count * 500L,
    )

    // ==================== the song band ====================

    /**
     * The dock's song band in a counting layer. The band's body collects the story once per run,
     * so [compositions] counts its runs; the layer's draws count what a band frame re-records around it.
     */
    private class BandScene(pulsar: PulsarFeature, tv: Boolean) {
        var compositions = 0
        var band = Rect.Zero
        var aroundDraws = 0
        private val counted = object : PulsarFeature by pulsar {
            override val songStoryFlow: StateFlow<SongStory> get() = pulsar.songStoryFlow.also { compositions++ }
        }
        val scene = ImageComposeScene(1280, 60, Density(1f)) {
            CompositionLocalProvider(LocalTelevisionHardware provides tv) {
                OrpheusTheme {
                    Box(Modifier.graphicsLayer().drawBehind { aroundDraws++ }) {
                        DockSongBand(counted, Modifier.onGloballyPositioned { band = it.boundsInRoot() })
                    }
                }
            }
        }
    }

    // Off TV the paused band zips, so it is never idle; it still must not recompose.
    @Test
    fun anUnrelatedPulsarChangeNeverRecomposesAZippingBand() {
        val state = MutableStateFlow(PulsarViewModel.previewFeature().stateFlow.value)
        val band = BandScene(pausedPulsar(state, story = MutableStateFlow(storyOf(120))), tv = false)
        try {
            (1_000L..1_600L step 100).forEach { band.scene.frame(it) }
            assertTrue(band.scene.hasInvalidations(), "a paused band with progress should be zipping")
            val seen = band.compositions
            state.value = state.value.copy(energy = 0.9f)
            Snapshot.sendApplyNotifications()
            band.scene.frame(1_700)
            assertEquals(seen, band.compositions, "an energy change recomposed the band")
        } finally {
            band.scene.close()
        }
    }

    // Nothing to zip and nothing playing: a paused band with no progress asks for no frames.
    @Test
    fun aPausedBandWithoutProgressAsksForNoFrames() {
        val base = pausedPulsar()
        val band = BandScene(
            object : PulsarFeature by base {
                override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(navState.copy(progress = null))
            },
            tv = false,
        )
        try {
            (1_000L..1_600L step 100).forEach { band.scene.frame(it) }
            assertFalse(band.scene.hasInvalidations(), "a paused band with no progress asks for frames")
        } finally {
            band.scene.close()
        }
    }

    // On TV the band redraws only when the playhead reaches another bar: a beat or a progress tick
    // inside one bar asks for no frame. 1240px of band at 5px a bar, so 0.62 reaches bar 154 and 0.624 bar 155.
    @Test
    fun onTelevisionTheBandRedrawsOnlyWhenThePlayheadReachesABar() {
        val story = MutableStateFlow(storyOf(8))
        val navFlow = MutableStateFlow(navState)
        val base = pausedPulsar(story = story)
        val band = BandScene(object : PulsarFeature by base { override val vibeNavFlow: StateFlow<VibeNavState> = navFlow }, tv = true)
        try {
            band.scene.frame(1_000)
            val before = band.scene.frame(1_100)
            assertFalse(band.scene.hasInvalidations(), "the TV band was not idle to begin with")
            val seen = band.compositions
            val loud = StoryBeat(1f, FloatArray(8).also { it[3] = 1f }, positionMs = 4_000L, durationMs = 500L)
            story.value = SongStory(story.value.beats + loud, elapsedMs = 4_500L)
            navFlow.value = navState.copy(progress = 0.621f)
            Snapshot.sendApplyNotifications()
            assertFalse(band.scene.hasInvalidations(), "a beat and a tick inside one bar redrew the TV band")
            assertTrue(before.contentEquals(band.scene.frame(1_200)), "the TV band changed inside one bar")
            navFlow.value = navState.copy(progress = 0.624f)
            Snapshot.sendApplyNotifications()
            assertTrue(band.scene.hasInvalidations(), "reaching the next bar did not redraw the TV band")
            assertFalse(before.contentEquals(band.scene.frame(1_300)), "the next bar never showed")
            assertEquals(seen, band.compositions, "the TV band recomposed")
        } finally {
            band.scene.close()
        }
    }

    // Playing on TV hardware: no pulse, no live head, no frames.
    @Test
    fun onTelevisionAPlayingBandAsksForNoFrames() {
        val band = BandScene(playingPulsar(MutableStateFlow(MusicPulse(1f, FloatArray(8).also { it[0] = 1f }, 0.2f, 500f))), tv = true)
        try {
            (1_000L..1_600L step 100).forEach { band.scene.frame(it) }
            assertFalse(band.scene.hasInvalidations(), "a playing TV band asks for frames")
        } finally {
            band.scene.close()
        }
    }

    private fun BandScene.pixels(ms: Long): PixelMap =
        Image.makeFromEncoded(scene.frame(ms)).toComposeImageBitmap().toPixelMap()

    private fun PixelMap.countIn(top: Float, bottom: Float, test: (Color) -> Boolean): Int {
        var count = 0
        for (y in top.toInt() until bottom.toInt()) for (x in 0 until width) if (test(this[x, y])) count++
        return count
    }

    private fun playingPulsar(pulse: StateFlow<MusicPulse>, nav: VibeNavState = navState): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = false))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
            override val songStoryFlow: StateFlow<SongStory> = MutableStateFlow(storyOf(120))
            override val musicPulseFlow: StateFlow<MusicPulse> = pulse
        }
    }

    // A 36dp row of bars on TV hardware too, where the bar's edge carries no hairline any more.
    @Test
    fun theSongBandIsItsOwnRowOnTelevisionToo() {
        listOf(false, true).forEach { tv ->
            val band = BandScene(pausedPulsar(story = MutableStateFlow(storyOf(120))), tv)
            try {
                band.scene.frame(1_000)
                val px = band.pixels(1_016)
                assertEquals(SongBandHeight.value, band.band.height, "the band is not 36dp (tv=$tv)")
                // Opaque: the bars, not the scrim under them, which never passes 0.55.
                val bars = px.countIn(band.band.top, band.band.bottom) { it.alpha > 0.9f }
                assertTrue(bars > 0, "the band drew no bars (tv=$tv)")
            } finally {
                band.scene.close()
            }
        }
    }

    // A counting draw in a layer around the band: a band frame re-records the band's own layer alone.
    @Test
    fun aBandFrameReRecordsNothingAroundIt() {
        val pulse = MutableStateFlow(MusicPulse.SILENT)
        val band = BandScene(playingPulsar(pulse), tv = false)
        try {
            band.scene.frame(1_000)
            band.scene.frame(1_016)
            val before = band.pixels(1_032)
            val seen = band.compositions
            val aroundDraws = band.aroundDraws
            repeat(12) { i ->
                pulse.value = MusicPulse(0.3f + i / 20f, FloatArray(8).also { it[i % 8] = 1f }, (i % 4) / 4f, 500f)
                Snapshot.sendApplyNotifications()
                band.scene.frame(1_048L + i * 16)
            }
            val after = band.pixels(1_248)
            var moved = false
            for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) moved = true
            assertTrue(moved, "the band never moved with the beat, so this proves nothing")
            assertEquals(seen, band.compositions, "a band frame recomposed the band")
            assertEquals(aroundDraws, band.aroundDraws, "a band frame re-recorded the layer around it")
        } finally {
            band.scene.close()
        }
    }

    // The zip needs an elapsed band of at least ZipMinLength; paused short of it, nothing asks for frames.
    @Test
    fun aPausedBandZipsOnlyOnceItHasSomethingToZip() {
        listOf(0.62f to true, 0.002f to false).forEach { (progress, zips) ->
            val base = pausedPulsar(story = MutableStateFlow(storyOf(120)))
            val short = object : PulsarFeature by base {
                override val vibeNavFlow: StateFlow<VibeNavState> =
                    MutableStateFlow(navState.copy(progress = progress, positionMs = (progress * 120_000L).toLong()))
            }
            val band = BandScene(short, tv = false)
            try {
                (1_000L..1_600L step 100).forEach { band.scene.frame(it) }
                assertEquals(zips, band.scene.hasInvalidations(), "a paused band at $progress")
            } finally {
                band.scene.close()
            }
        }
    }

    @Test
    fun aPulseNeverRecomposesTheBarOrTheNavScaffold() {
        val base = PulsarViewModel.previewFeature()
        val pulse = MutableStateFlow(MusicPulse.SILENT)
        var scaffoldRuns = 0
        val pulsar = object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = false))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(navState)
            override val musicPulseFlow: StateFlow<MusicPulse> = pulse
            // The scaffold reads this once per run of its own body.
            override val actions get() = base.actions.also { scaffoldRuns++ }
        }
        val bar = BarScene(pulsar, tv = false)
        val nav = ImageComposeScene(360, 780, Density(1f)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab }, onItemClick = {}, layout = DjLayout.Portrait, pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                ) { Box(Modifier) }
            }
        }
        try {
            // The second frame lands the recomposition the first layout's measured widths ask for.
            bar.scene.frame(1_000)
            bar.scene.frame(1_008)
            nav.frame(1_000)
            nav.frame(1_008)
            val seenBar = bar.compositions
            val seenNav = scaffoldRuns
            repeat(12) { i ->
                pulse.value = MusicPulse(i / 12f, FloatArray(8).also { it[i % 8] = 1f }, (i % 4) / 4f, 500f)
                Snapshot.sendApplyNotifications()
                bar.scene.frame(1_016L + i * 16)
                nav.frame(1_016L + i * 16)
            }
            assertEquals(seenBar, bar.compositions, "a pulse recomposed the bar")
            assertEquals(seenNav, scaffoldRuns, "a pulse recomposed the nav scaffold")
        } finally {
            bar.scene.close()
            nav.close()
        }
    }
}

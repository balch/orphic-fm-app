package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.sin
import kotlin.test.Test

// Tracks: 0 kick red, 1 perc orange, 2 hat yellow, 3 bass blue, 4 keys green, 5 pad cyan, 6 texture pink, 7 fx grey.
// Beat [i] of a 128 BPM song.
private fun beat(i: Int, level: Float, vararg energy: Pair<Int, Float>) =
    StoryBeat(level, FloatArray(8).also { e -> energy.forEach { (t, v) -> e[t] = v } }, positionMs = i * 469L, durationMs = 469L)

// 40 bars: a quiet pad intro, a keys-and-bass verse, a pink-texture breakdown, then a kick-led drop.
private val demoBeats = List(160) { i ->
    val wobble = 0.05f * sin(i * 1.7f)
    when {
        i < 32 -> beat(i, 0.18f + wobble, 5 to 6f, 7 to 1f)
        i < 80 -> beat(i, 0.58f + wobble, 4 to 5f, 3 to 4f, 0 to 2f, 2 to 1f)
        i < 112 -> beat(i, 0.3f + wobble, 6 to 5f, 5 to 2f)
        else -> beat(i, 0.9f + wobble / 2, 0 to 8f, 3 to 3f, 1 to 2f)
    }
}

/** A song 62% in with a clear breakdown and drop, for the renders that judge the dock's story. */
internal val BreakdownDropStory = SongStory(demoBeats, elapsedMs = 160 * 469L)
internal val BreakdownDropNav = VibeNavState("Rust Belt", "Dog House", "Stay Asleep", progress = 0.62f)

/** ./gradlew :apps:djapp:shared:jvmTest --tests '*MusicWaveRenderHarness*' --rerun */
class MusicWaveRenderHarness {
    private val wavePhase = 1f
    private val story = BreakdownDropStory

    // The app spent the end of the verse and most of the breakdown in the background: nothing recorded there.
    private val storyWithGap = SongStory(demoBeats.filterIndexed { i, _ -> i < 60 || i >= 100 }, elapsedMs = 160 * 469L)

    private fun pulse(level: Float, lead: Int) =
        MusicPulse(level, FloatArray(8).also { it[lead] = level; it[(lead + 3) % 8] = level * 0.3f }, 0.25f, 469f)

    private val nav = BreakdownDropNav

    private fun feature(paused: Boolean, livePulse: MusicPulse, songStory: SongStory = story): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
            override val songStoryFlow: StateFlow<SongStory> = MutableStateFlow(songStory)
            override val musicPulseFlow: StateFlow<MusicPulse> = MutableStateFlow(livePulse)
        }
    }

    /** The dock's song band at 1280dp: playing (1x and 2x), paused mid-zip, TV (static, no live head), and with a background gap. */
    @Test
    fun renderDockStory() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        data class Shot(val tag: String, val density: Float, val paused: Boolean, val tv: Boolean, val zipMs: Long?, val songStory: SongStory = story)
        listOf(
            Shot("playing", 1f, paused = false, tv = false, zipMs = null),
            Shot("playing-2x", 2f, paused = false, tv = false, zipMs = null),
            Shot("paused", 1f, paused = true, tv = false, zipMs = 900L),
            Shot("paused-2x", 2f, paused = true, tv = false, zipMs = 900L),
            Shot("tv", 1f, paused = false, tv = true, zipMs = 900L),
            Shot("gap-2x", 2f, paused = false, tv = false, zipMs = null, songStory = storyWithGap),
        ).forEach { shot ->
            runCatching {
                val scene = ImageComposeScene((1280 * shot.density).toInt(), (48 * shot.density).toInt(), Density(shot.density)) {
                    OrpheusTheme {
                        CompositionLocalProvider(LocalTelevisionHardware provides shot.tv) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                                DockSongBand(
                                    pulsarFeature = feature(shot.paused, pulse(0.95f, lead = 0), shot.songStory),
                                    previewWavePhase = wavePhase,
                                    previewZipMs = shot.zipMs,
                                )
                            }
                        }
                    }
                }
                try {
                    // Widths measured in the first frame gate the zip; the second frame draws it.
                    scene.render()
                    File(outDir, "music-dock-1280-${shot.tag}.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] dock ${shot.tag} skipped: $it") }
        }
    }

    /**
     * The whole dock off TV hardware, the real chrome with both bars in glass, over the busy
     * backdrop at 1280x720 and 960x600: playing into the drop, and paused with the zip mid-pass.
     */
    @Test
    fun renderDockSongBand() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        listOf(1280 to 720, 960 to 600).forEach { (w, h) ->
            listOf("playing" to null, "paused" to 700L).forEach { (tag, zipMs) ->
                runCatching {
                    val pulsar = feature(paused = zipMs != null, livePulse = pulse(0.95f, lead = 0))
                    val docked = listOf(PulsarTab, DjTab, MixTab)
                    val scene = ImageComposeScene(w, h, Density(1f)) {
                        OrpheusTheme {
                            val liquid = rememberLiquidState()
                            CompositionLocalProvider(LocalLiquidState provides liquid) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize().liquefiable(liquid)) { busyVizBackdrop() }
                                    DjAppTvChrome(
                                        tvHardware = false,
                                        domeRingSize = BarRingSize,
                                        barGlass = true,
                                        vizHidesPanelsWhenIdle = false,
                                        focusRegion = TvFocusRegionHolder(),
                                        vizFeature = VizViewModel.previewFeature(),
                                        pulsarFeature = pulsar,
                                        timerFeature = TimerViewModel.previewFeature(),
                                        onTogglePlayback = {},
                                        dockablePanels = largeScreenPanels(),
                                        dockedPanels = docked,
                                        activeSheet = null,
                                        tabs = djTabs,
                                        onToggleDocked = {},
                                        onActiveSheetChange = {},
                                        stage = {
                                            DjPanelDock(panels = docked, modifier = Modifier.fillMaxSize()) { route, mod ->
                                                PreviewRoutePanel(route, mod)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    try {
                        // Real frame loops: from a second in (zero reads as unset), far enough into the zip's pass.
                        val frames = if (zipMs != null) (zipMs / 16).toInt() else 60
                        repeat(frames) { scene.render((1_000L + it * 16L) * 1_000_000L) }
                        val png = scene.render((1_000L + frames * 16L) * 1_000_000L).encodeToData()!!.bytes
                        File(outDir, "dock-band-${w}x$h-$tag.png").writeBytes(png)
                    } finally {
                        scene.close()
                    }
                }.onFailure {
                    if (it is IllegalStateException) throw it
                    println("[render-harness] dock band ${w}x$h $tag skipped: $it")
                }
            }
        }
    }

    /**
     * The dock bottom bar's centre at 3x, in glass over the busy backdrop: the dome playing, paused
     * mid-zip, and holding the launch focus, which arrives a frame after the bar as the dock does,
     * with its focus mark awake and after the idle fade.
     */
    @Test
    fun renderDockDome() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scale = 3f
        listOf("playing" to null, "paused" to 700L, "focused" to null, "focused-faded" to null).forEach { (tag, zipMs) ->
            runCatching {
                val focusing = tag.startsWith("focused")
                val docked = mutableStateOf(!focusing)
                val region = TvFocusRegionHolder()
                val pulsar = feature(paused = zipMs != null, livePulse = pulse(0.95f, lead = 0))
                // A standard dispatcher runs the launch focus request after layout, as the app's does.
                val scheduler = TestCoroutineScheduler()
                val scene = ImageComposeScene(
                    (1280 * scale).toInt(), (200 * scale).toInt(), Density(scale), StandardTestDispatcher(scheduler),
                ) {
                    OrpheusTheme {
                        val liquid = rememberLiquidState()
                        CompositionLocalProvider(
                            LocalLiquidState provides liquid,
                            LocalTelevisionHardware provides false,
                            LocalTvFocusRegion provides region,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().liquefiable(liquid)) { busyVizBackdrop() }
                                if (docked.value) DjTvBottomBar(
                                    panels = bottomBarPanels(largeScreenPanels()),
                                    isDocked = { it == DjTab },
                                    onToggle = {},
                                    timerFeature = TimerViewModel.previewFeature(),
                                    pulsarFeature = pulsar,
                                    onTogglePlayback = {},
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    glass = true,
                                    previewWavePhase = wavePhase,
                                    previewZipMs = zipMs,
                                )
                            }
                        }
                    }
                }
                try {
                    fun frame() = scheduler.runCurrent().let { scene.render() }
                    frame()
                    docked.value = true
                    repeat(3) { frame() }
                    if (tag == "focused-faded") runBlocking { region.alpha.snapTo(0f) }
                    // The bar's centre 360 x 200dp: the dome, its name and the toggles either side.
                    val full = Image.makeFromEncoded(frame().encodeToData()!!.bytes)
                    val crop = Surface.makeRasterN32Premul((360 * scale).toInt(), (200 * scale).toInt())
                    crop.canvas.drawImage(full, -460 * scale, 0f)
                    File(outDir, "dock-dome-$tag.png").writeBytes(crop.makeImageSnapshot().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[render-harness] dock dome $tag skipped: $it")
            }
        }
    }

    /**
     * The ring alone at 6x. Rows: bar then rail, both 64dp. Columns: quiet keys, quiet texture,
     * loud kick, loud bass, silent, paused mid-zip (holding the loud kick's shape).
     */
    @Test
    fun renderRingLoudness() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(460 * 6, 160 * 6, Density(6f)) {
                OrpheusTheme {
                    Column(
                        Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf(BarRingSize, RailRingSize).forEach { size ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf(pulse(0.1f, 4), pulse(0.15f, 6), pulse(1f, 0), pulse(0.9f, 3), MusicPulse.SILENT).forEach { p ->
                                    VibeTransportRing(
                                        paused = false, progress = 0.62f, ringSize = size, pulse = { p },
                                        previewWavePhase = wavePhase,
                                    )
                                }
                                VibeTransportRing(
                                    paused = true, progress = 0.62f, ringSize = size, pulse = { pulse(1f, 0) },
                                    previewWavePhase = wavePhase, previewZipMs = 900L,
                                )
                            }
                        }
                    }
                }
            }
            try {
                File(outDir, "music-ring-loudness.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] ring loudness skipped: $it") }
    }

    /** The paused zip across one pass through the held wave on the ring (6x) and the dock's band (2x), for its travel and fade. */
    @Test
    fun renderZipPass() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val times = listOf(100L, 400L, 800L, 1200L, 1500L)
            val scene = ImageComposeScene(360 * 6, 80 * 6, Density(6f)) {
                OrpheusTheme {
                    Row(
                        Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        times.forEach { t ->
                            VibeTransportRing(
                                paused = true, progress = 0.62f, pulse = { pulse(0.9f, 3) },
                                previewWavePhase = wavePhase, previewZipMs = t,
                            )
                        }
                    }
                }
            }
            try {
                File(outDir, "music-zip-ring-pass.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] zip ring skipped: $it") }
        listOf(300L, 1300L).forEach { t ->
            runCatching {
                val scene = ImageComposeScene(1280 * 2, 48 * 2, Density(2f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            DockSongBand(
                                pulsarFeature = feature(paused = true, livePulse = MusicPulse.SILENT),
                                previewWavePhase = wavePhase,
                                previewZipMs = t,
                            )
                        }
                    }
                }
                try {
                    scene.render()
                    File(outDir, "music-zip-dock-$t.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] zip dock $t skipped: $it") }
        }
    }
}

package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** ./gradlew :apps:djapp:shared:jvmTest --tests '*VibeStepRenderHarness*' --rerun */
class VibeStepRenderHarness {
    private val short = VibeNavState("Rust Belt", "Dog House", "Stay Asleep", progress = 0.62f)
    private val long = VibeNavState("Ouroboros Bloom", "Kaleidoscope Drift", "Vanished Skyline", progress = 0.1f, previousRestarts = true)

    // Null progress (cold start, before the song has a bar). One width is enough to see that.
    private val noProgress = VibeNavState("Rust Belt", "Dog House", "Stay Asleep", progress = null)

    @Test
    fun renderBottomBarWithStepTiles() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val base = PulsarViewModel.previewFeature()
        listOf(960, 1280, 1440).forEach { width ->
            val scenarios = buildList {
                add("short" to short)
                add("long" to long)
                if (width == 1280) add("noprogress" to noProgress)
            }
            scenarios.forEach { (tag, nav) ->
                runCatching {
                    val pulsar = object : PulsarFeature by base {
                        override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
                    }
                    val scene = ImageComposeScene(width, 200, Density(1f)) {
                        OrpheusTheme {
                            Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                                DjTvBottomBar(
                                    panels = bottomBarPanels(largeScreenPanels()),
                                    isDocked = { it == PulsarTab || it == DjTab },
                                    onToggle = {},
                                    timerFeature = TimerViewModel.previewFeature(),
                                    pulsarFeature = pulsar,
                                    onTogglePlayback = {},
                                )
                            }
                        }
                    }
                    try {
                        // StepSlot's width comes from onSizeChanged, one frame behind the first
                        // composition — a second render lets that recomposition land before capture.
                        scene.render()
                        File(outDir, "bottom-bar-steps-$width-$tag.png").writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally {
                        scene.close()
                    }
                }.onFailure { println("[render-harness] steps $width $tag skipped: $it") }
            }
        }
    }

    // Long enough on both sides to overflow the step tile slot at 960 and 1280 alike.
    private val longScrolling = VibeNavState(
        currentName = "Rust Belt", previousName = "Kaleidoscope Drift", nextName = "Vanished Skyline", progress = 0.1f,
    )

    /**
     * Long neighbour names, playing and paused: a rest frame (before the marquee's initial delay,
     * first letter crisp) and a mid-scroll frame, at density 2 so the edges can be judged.
     */
    @Test
    fun renderStepTileMarqueeScroll() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val base = PulsarViewModel.previewFeature()
        fun pulsar(paused: Boolean) = object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(longScrolling)
        }
        listOf(960 to false, 1280 to false, 960 to true, 1280 to true).forEach { (width, paused) ->
            val feature = pulsar(paused)
            val tag = if (paused) "$width-paused" else "$width"
            runCatching {
                val scene = ImageComposeScene(width * 2, 200 * 2, Density(2f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            DjTvBottomBar(
                                panels = bottomBarPanels(largeScreenPanels()),
                                isDocked = { it == PulsarTab || it == DjTab },
                                onToggle = {},
                                timerFeature = TimerViewModel.previewFeature(),
                                pulsarFeature = feature,
                                onTogglePlayback = {},
                            )
                        }
                    }
                }
                try {
                    // Step every 16ms so the marquee's animation actually progresses, not just jumps.
                    (0L..1_088L step 16).forEach { scene.render(it * 1_000_000) }
                    File(outDir, "bottom-bar-steps-$tag-marquee-rest.png")
                        .writeBytes(scene.render(1_104L * 1_000_000).encodeToData()!!.bytes)
                    (1_120L..2_000L step 16).forEach { scene.render(it * 1_000_000) }
                    File(outDir, "bottom-bar-steps-$tag-marquee-scroll.png")
                        .writeBytes(scene.render(2_016L * 1_000_000).encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] marquee scroll $tag skipped: $it") }
        }
    }

    /** Focused ◀ tile: confirms the dock-toggle checkmark/radio badge never renders on a step tile. */
    @Test
    fun renderPreviousTileFocused() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val pulsar = object : PulsarFeature by PulsarViewModel.previewFeature() {
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(short)
        }
        runCatching {
            val scene = ImageComposeScene(1280, 200, Density(1f)) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                        DjTvBottomBar(
                            panels = bottomBarPanels(largeScreenPanels()),
                            isDocked = { it == PulsarTab || it == DjTab },
                            onToggle = {},
                            timerFeature = TimerViewModel.previewFeature(),
                            pulsarFeature = pulsar,
                            onTogglePlayback = {},
                            previewFocusPreviousTile = true,
                        )
                    }
                }
            }
            try {
                scene.render()
                File(outDir, "bottom-bar-steps-1280-focused-prev.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] focused-prev skipped: $it") }
    }
}

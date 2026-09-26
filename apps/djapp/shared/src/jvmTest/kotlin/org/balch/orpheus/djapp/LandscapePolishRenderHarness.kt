package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** The user's 737x606dp and 747x612dp desktop windows in Landscape. ./gradlew :apps:djapp:shared:jvmTest --tests '*LandscapePolishRenderHarness*' --rerun */
class LandscapePolishRenderHarness {
    @Test
    fun renderLandscapeWindow() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(737 * 2, 606 * 2, Density(2f)) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab },
                        onItemClick = {},
                        layout = DjLayout.Landscape,
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize().background(busyBars()),
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            PulsarPanel(
                                pulsar = PulsarViewModel.previewFeature(),
                                modifier = Modifier.weight(.5f).fillMaxHeight(),
                                isExpanded = true, onExpandedChange = {},
                                showCollapsedHeader = false, showExpandedTitle = false,
                                centerContent = false,
                            )
                            Box(Modifier.weight(.5f).fillMaxHeight())
                        }
                    }
                }
            }
            try {
                File(outDir, "landscape-737x606.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] landscape skipped: $it") }
    }

    // The user's screenshot content (ENV on WAVES): the preview's "Preview"/AD fit anywhere, so cannot show the clip.
    private val spaceAndDrift: PulsarFeature = namedVibe("Space & Drift")

    @Test
    fun renderLandscapeWindowLongName() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(737 * 2, 606 * 2, Density(2f)) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab },
                        onItemClick = {},
                        layout = DjLayout.Landscape,
                        pulsarFeature = spaceAndDrift,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize().background(busyBars()),
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            PulsarPanel(
                                pulsar = spaceAndDrift,
                                modifier = Modifier.weight(.5f).fillMaxHeight(),
                                isExpanded = true, onExpandedChange = {},
                                showCollapsedHeader = false, showExpandedTitle = false,
                                centerContent = false,
                            )
                            Box(Modifier.weight(.5f).fillMaxHeight())
                        }
                    }
                }
            }
            try {
                // The rail picks its ring from the height it measured last frame.
                scene.render()
                File(outDir, "landscape-737x606-longname.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] landscape longname skipped: $it") }
    }

    /** A Pulsar whose nav state names [name], so the rail label under the ring shows it. */
    private fun namedVibe(name: String): PulsarFeature = PulsarViewModel.previewFeature(
        PulsarUiState(
            vibe = PulsarViewModel.previewFeature().vibeList.first().copy(name = name),
            envelopeMode = 1,
        ),
    ).let { base ->
        object : PulsarFeature by base {
            override val vibeNavFlow: StateFlow<VibeNavState> =
                MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
        }
    }

    private fun playing(feature: PulsarFeature): PulsarFeature = object : PulsarFeature by feature {
        override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(feature.stateFlow.value.copy(globalPaused = false))
    }

    /**
     * The real Landscape branch of DjAppMainContent, header included; by default the user's 747x612dp
     * window. Each shot is written at its time in ms, stepped 16ms at a time so a scrolling name moves.
     */
    private fun renderScreen(
        tag: String,
        pulsar: PulsarFeature,
        width: Int = 747,
        height: Int = 612,
        fontScale: Float = 1f,
        shots: List<Pair<String, Long>> = listOf("" to 16L),
    ) = runCatching {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val empty = MutableStateFlow(FloatArray(0))
        val scene = ImageComposeScene(width * 2, height * 2, Density(2f, fontScale)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab },
                    onItemClick = {},
                    layout = DjLayout.Landscape,
                    pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(),
                    onTogglePlayback = {},
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B24)),
                ) {
                    DjAppMainContent(
                        layout = DjLayout.Landscape,
                        dockedPanels = emptyList(),
                        pairPanels = emptyList(),
                        pulsarFeature = pulsar,
                        synthEngine = RenderProbeSynthEngine(),
                        vizFeature = VizViewModel.previewFeature(),
                        onShowVibeInfo = {},
                        routePanel = { _, _, _ -> },
                        // Production's navContent is a NavDisplay showing the DJ tab.
                        navContent = { mod ->
                            Box(mod) {
                                DjPanel(
                                    feature = DjViewModel.previewFeature(),
                                    vizFlowA = empty, vizFlowB = empty, outVizFlow = empty,
                                    modifier = Modifier.fillMaxSize(),
                                    isExpanded = true, onExpandedChange = {},
                                    showCollapsedHeader = false, showExpandedTitle = false,
                                )
                            }
                        },
                    )
                }
            }
        }
        try {
            // The first frames also let the rail pick its transport from the height it measured.
            var ms = 0L
            shots.forEach { (suffix, atMs) ->
                while (ms < atMs) {
                    scene.render(ms * 1_000_000)
                    ms += 16
                }
                File(outDir, "landscape-screen-${width}x$height-$tag$suffix.png")
                    .writeBytes(scene.render(atMs * 1_000_000).encodeToData()!!.bytes)
                ms = atMs + 16
            }
        } finally {
            scene.close()
        }
    }.onFailure { println("[render-harness] landscape screen $tag skipped: $it") }

    @Test
    fun renderLandscapeScreen() {
        renderScreen("lost-in-space", namedVibe("Lost In Space"))
        renderScreen("kaleidoscope-drift", namedVibe("Kaleidoscope Drift"))
    }

    // The Fold 8's cover screen sideways, 840x360dp at the user's 1.3 font scale: the rail's title,
    // Pulsar's knob labels and the DJ panel must all fit. Rest is before the marquee's first pass.
    @Test
    fun renderFoldCoverScreen() {
        val kaleidoscope = namedVibe("Kaleidoscope Drift")
        renderScreen(
            "kaleidoscope-drift-playing", playing(kaleidoscope), width = 840, height = 360, fontScale = 1.3f,
            shots = listOf("-rest" to 1_104L, "-scroll" to 2_016L),
        )
        renderScreen("kaleidoscope-drift-paused", kaleidoscope, width = 840, height = 360, fontScale = 1.3f)
        renderScreen("kaleidoscope-drift-fontscale1", kaleidoscope, width = 840, height = 360)
    }

    // Stand-in for the spectrum viz: saturated vertical bars behind the rail, where the user saw labels vanish.
    private fun busyBars() = androidx.compose.ui.graphics.Brush.horizontalGradient(
        List(12) { if (it % 2 == 0) Color(0xFFFF2BD6) else Color(0xFF7B3CFF) },
    )
}

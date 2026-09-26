package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole Landscape screen, rail to DJ panel, on the Fold 8's cover screen: 840x360dp at its 1.3
 * font scale. Each bottom-most text must be as tall as on a tall window (neither clipped nor
 * squeezed) and inside the screen. One density unit per pixel, so the bounds read in dp.
 */
class LandscapeFitTest {
    private val name = "Kaleidoscope Drift"
    private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
    }

    // Pulsar's macro knob labels, the DJ panel's readouts under its platters, and the rail's title.
    private val bottomTexts = listOf("ENERGY", "COMPLEXITY", "MOOD", "SPACE", "MIX", "0.0", name)

    /** Every unmerged text node's bounds, by its text. */
    private fun texts(width: Int, height: Int, fontScale: Float): Map<String, List<Rect>> {
        val empty = MutableStateFlow(FloatArray(0))
        val scene = ImageComposeScene(width, height, Density(1f, fontScale)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab },
                    onItemClick = {},
                    layout = DjLayout.Landscape,
                    pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(),
                    onTogglePlayback = {},
                    modifier = Modifier.fillMaxSize(),
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
            // The rail and Pulsar size themselves from what they measured the frame before.
            repeat(3) { scene.render() }
            val out = mutableMapOf<String, MutableList<Rect>>()
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { out.getOrPut(it.text) { mutableListOf() } += node.boundsInRoot }
                node.children.forEach(::walk)
            }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        } finally {
            scene.close()
        }
    }

    @Test
    fun theFoldCoverScreenKeepsEveryBottomTextWhole() {
        val tall = texts(747, 612, fontScale = 1.3f)
        val cover = texts(840, 360, fontScale = 1.3f)
        bottomTexts.forEach { text ->
            val whole = tall.getValue(text).first().height
            val shown = cover[text].orEmpty()
            assertTrue(shown.isNotEmpty(), "no $text on the cover screen")
            shown.forEach { bounds ->
                assertEquals(whole, bounds.height, 0.5f, "$text is ${bounds.height}dp tall on the cover screen, $whole on a tall window")
                assertTrue(bounds.bottom <= 360f - 2f, "$text ends at ${bounds.bottom}dp on a 360dp screen")
            }
        }
    }
}

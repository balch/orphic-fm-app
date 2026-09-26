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
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 28A: VIBE/ROOT/SCALE/ENV's priority-fit row. VIBE gets its whole name up to
 * `VibeValueMaxWidth` before SCALE gives way past its floor, and ROOT/ENV always keep their
 * natural width. Real names at real host widths, asserted through semantics bounds the way
 * `RailLayoutTest` reads the nav rail: `SemanticsProperties.Text` carries the full string
 * regardless of visual ellipsis, so "ellipsised" is read as "narrower than its own natural width",
 * measured by rendering the same row at 2000dp where nothing is squeezed.
 */
class PulsarSelectorRowTest {

    private class Node(val texts: List<String>, val bounds: Rect)

    private val previewVibe = PulsarViewModel.previewFeature().vibeList.first()

    private val techno = PulsarUiState(
        vibe = previewVibe.copy(name = "Techno Wobble"),
        rootNote = 1,       // C#
        scaleIndex = 2,     // Pentatonic
        envelopeMode = 2,   // BLEND
    )

    // The Fold 8 cover screen's landscape column is 362dp (DjLayoutRenderHarness
    // .renderPulsarLandscapeFoldSweep), but a single-destination portrait phone hands the panel
    // its full screen width with no side inset (DjLayoutBox is edge-to-edge) -- nav-portrait-360
    // in NavTransportRenderHarness is the narrowest of those. 360dp is the narrowest real width.
    private val narrowWidth = 360

    // A closed iPhone Duo (DjLayoutRenderHarness.renderPulsarCompactSweep): comfortably wider,
    // where both VIBE and SCALE fit at their natural size.
    private val comfortableWidth = 466

    private fun render(width: Int, uiState: PulsarUiState, showVibe: Boolean = true): List<Node> {
        val scene = ImageComposeScene(width, 200, Density(1f)) {
            OrpheusTheme {
                Box(Modifier.fillMaxSize()) {
                    PulsarPanel(
                        pulsar = PulsarViewModel.previewFeature(uiState),
                        isExpanded = true,
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                        showVibePicker = showVibe,
                    )
                }
            }
        }
        try {
            scene.render()
            fun collect(root: SemanticsNode): List<Node> {
                val out = mutableListOf<Node>()
                fun walk(node: SemanticsNode) {
                    out += Node(
                        texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                        bounds = node.boundsInRoot,
                    )
                    node.children.forEach(::walk)
                }
                walk(root)
                return out
            }
            return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
        } finally {
            scene.close()
        }
    }

    private fun List<Node>.value(text: String): Node =
        this.first { text in it.texts }

    @Test
    fun atTheNarrowestWidthVibeIsAtLeastAsWideAsScaleAndNothingClips() {
        val nodes = render(narrowWidth, techno)
        val vibe = nodes.value("Techno Wobble")
        val scale = nodes.value("Pentatonic")
        val root = nodes.value("C#")
        val env = nodes.value("BLEND")
        val naturalRoot = render(2000, techno).value("C#").bounds.width
        val naturalEnv = render(2000, techno).value("BLEND").bounds.width

        assertTrue(
            vibe.bounds.width >= scale.bounds.width,
            "VIBE (${vibe.bounds.width}px) is narrower than SCALE (${scale.bounds.width}px)",
        )
        assertTrue(root.bounds.width >= naturalRoot - 1f, "ROOT shrank: ${root.bounds.width}px of ${naturalRoot}px natural")
        assertTrue(env.bounds.width >= naturalEnv - 1f, "ENV shrank: ${env.bounds.width}px of ${naturalEnv}px natural")
        assertTrue(env.bounds.right <= narrowWidth + 1f, "ENV spills past the ${narrowWidth}dp row: ${env.bounds}")
    }

    @Test
    fun atAComfortableWidthNothingIsEllipsised() {
        val natural = render(2000, techno)
        val shown = render(comfortableWidth, techno)

        for (text in listOf("Techno Wobble", "C#", "Pentatonic", "BLEND")) {
            val naturalWidth = natural.value(text).bounds.width
            val shownWidth = shown.value(text).bounds.width
            assertTrue(
                shownWidth >= naturalWidth - 1f,
                "\"$text\" shows ${shownWidth}px of its ${naturalWidth}px natural width at ${comfortableWidth}dp",
            )
        }
        val vibe = shown.value("Techno Wobble")
        val scale = shown.value("Pentatonic")
        assertTrue(vibe.bounds.width >= scale.bounds.width, "VIBE narrower than SCALE at a comfortable width")
    }

    @Test
    fun vibeNeverGrowsPastItsMaxWidth() {
        val kaleidoscope = PulsarUiState(vibe = previewVibe.copy(name = "Kaleidoscope Drift"))
        val vibe = render(1000, kaleidoscope).value("Kaleidoscope Drift")
        // VibeValueMaxWidth is 160dp = 160px at this scene's density(1f); the widest catalog name
        // (112px) sits well under it, so this is a ceiling check, not a "hits the cap" one -- see
        // the next test for a name long enough to actually engage it.
        assertTrue(vibe.bounds.width <= 161f, "VIBE grew past its 160dp cap: ${vibe.bounds.width}px")
    }

    @Test
    fun aNameLongerThanTheCatalogActuallyEngagesTheCap() {
        // EnumDropdown applies VibeValueMaxWidth internally, so even an unbounded scene can't show
        // this text's true (wider) natural width -- that ceiling is the behavior under test. At
        // 1000dp, ROOT ("D") and ENV ("AD") are tiny, so the row's own priority math never squeezes
        // VIBE below its cap here; whatever width shows is the cap alone, not a SCALE-floor pinch.
        val longName = "Kaleidoscope Drift Kaleidoscope Drift"
        val state = PulsarUiState(vibe = previewVibe.copy(name = longName))
        val shown = render(1000, state).value(longName).bounds.width
        assertTrue(shown in 158f..161f, "capped value should sit at ~160dp, was ${shown}px")
    }

    @Test
    fun dockedRowWithoutVibeIsUnchanged() {
        val natural = render(2000, techno, showVibe = false)
        val nodes = render(narrowWidth, techno, showVibe = false)
        for (text in listOf("C#", "Pentatonic", "BLEND")) {
            val naturalWidth = natural.value(text).bounds.width
            val shownWidth = nodes.value(text).bounds.width
            assertTrue(
                shownWidth >= naturalWidth - 1f,
                "\"$text\" shrank to ${shownWidth}px of ${naturalWidth}px with VIBE hidden",
            )
        }
    }
}

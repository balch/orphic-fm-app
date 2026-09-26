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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The landscape rail, asserted through its semantics bounds: tabs at the top, the ring pinned to
 * the bottom with no ◀ ▶ of its own, and a smaller ring when the full one would cut off the title.
 * One density unit per pixel, so the bounds read in dp; font scale 1.3 is the user's Fold 8.
 */
class RailLayoutTest {
    private val name = "Space & Drift"
    private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
    }

    private class Node(val descriptions: List<String>, val texts: List<String>, val bounds: Rect)

    private inner class Rail(val height: Float, val fontScale: Float, val nodes: List<Node>, val unmerged: List<Node>) {
        fun tab(label: String): Node = assertNotNull(nodes.firstOrNull { label in it.texts }, "no $label tab")
        fun described(label: String): Node? = nodes.firstOrNull { label in it.descriptions }
        val ring: Node get() = assertNotNull(nodes.firstOrNull { d -> d.descriptions.any { it.endsWith(", $name") } }, "no ring")
        // Unmerged: the ring's node merges the title into itself.
        val title: Node get() = assertNotNull(unmerged.firstOrNull { name in it.texts }, "no title")
        val labelLine get() = 16f * fontScale
    }

    private fun rail(width: Int, height: Int, fontScale: Float = 1f): Rail {
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
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        try {
            // The rail picks its transport from the height it measured the frame before.
            scene.render()
            scene.render()
            fun collect(root: SemanticsNode): List<Node> {
                val out = mutableListOf<Node>()
                fun walk(node: SemanticsNode) {
                    out += Node(
                        descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
                        texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                        bounds = node.boundsInRoot,
                    )
                    node.children.forEach(::walk)
                }
                walk(root)
                return out
            }
            return Rail(
                height = height.toFloat(),
                fontScale = fontScale,
                nodes = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) },
                unmerged = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) },
            )
        } finally {
            scene.close()
        }
    }

    private fun Rail.assertTabsTopTransportBottom(transportBottom: Node) {
        val tabs = djTabs.map { tab(it.label) }
        assertTrue(tabs.first().bounds.top < 24f, "the first tab sits at ${tabs.first().bounds.top}, not the top")
        tabs.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bounds.bottom <= lower.bounds.top, "tabs out of order") }
        assertTrue(tabs.last().bounds.bottom <= ring.bounds.top, "the transport overlaps the tabs")
        val gap = height - transportBottom.bounds.bottom
        assertTrue(gap in 0f..24f, "the transport ends ${gap}dp above the rail's bottom, not pinned to it")
    }

    // The user's Fold report: a squeezed transport measures its title short, so check the whole line.
    private fun Rail.assertTitleWhole() {
        val bounds = title.bounds
        assertTrue(bounds.height >= labelLine - 1f, "the title is ${bounds.height}dp of its ${labelLine}dp line")
        // Inside the rail's 4dp content padding and its 80dp width.
        assertTrue(bounds.bottom <= height - 4f, "the title ends at ${bounds.bottom}dp in a ${height}dp rail")
        assertTrue(bounds.left >= 0f && bounds.right <= 80f, "the title spills sideways: $bounds")
    }

    /** The ring's diameter: its node holds 4dp padding, the ring and the title. */
    private val Rail.ringSize: Float get() = ring.bounds.height - title.bounds.height - 8f

    /** What the rail rendered, top to bottom, against what the tier function budgets for it. */
    private fun Rail.assertBudgetIsMeasured(tier: RailTransport) {
        // Two 4dp rail gaps around the spacer, the transport's 8dp bottom padding, the rail's 4dp.
        val natural = tab(djTabs.last().label).bounds.bottom + 8f + ring.bounds.height + 8f + 4f
        val budget = railMinHeight(tier, djTabs.size, labelLine.dp).value
        assertTrue(natural <= budget, "the rail needs ${natural}dp but $tier budgets ${budget}dp")
        assertTrue(budget - natural <= 5f, "$tier budgets ${budget}dp for a ${natural}dp rail")
    }

    /** The dome's drag skips, as in the phone bar: no ◀ or ▶ of the rail's own. */
    private fun Rail.assertNoSkipButtons() {
        listOf("Previous vibe", "Restart vibe", "Next vibe").forEach { label ->
            assertEquals(null, described(label), "the rail shows a \"$label\" button at ${height}dp")
        }
    }

    /** The whole transport check at one size: [tier]'s ring pinned under the tabs, title whole. */
    private fun Rail.assertRing(tier: RailTransport) {
        assertNoSkipButtons()
        assertEquals(tier.ringSize.value, ringSize, 1f)
        assertTabsTopTransportBottom(transportBottom = ring)
        assertTitleWhole()
        assertBudgetIsMeasured(tier)
    }

    // The user's desktop window.
    @Test
    fun aTallRailPinsTheFullRingAtTheBottom() = rail(737, 606).assertRing(RailTransport.Standard)

    // A sideways phone.
    @Test
    fun aShortRailPinsTheFullRingAtTheBottom() = rail(915, 412).assertRing(RailTransport.Standard)

    // The Fold 8's cover screen sideways at its 1.3 font scale: the title was cut off here.
    @Test
    fun theFoldCoverScreenKeepsTheWholeTitle() = rail(840, 360, fontScale = 1.3f).assertRing(RailTransport.Compact)

    // The full ring's budget holds at the Fold's font scale on taller rails too.
    @Test
    fun atTheFoldsFontScaleTheTallerRailsKeepTheFullRing() {
        rail(737, 606, fontScale = 1.3f).assertRing(RailTransport.Standard)
        rail(915, 412, fontScale = 1.3f).assertRing(RailTransport.Standard)
    }

    // At font scale 1 the same 360dp keeps the full ring: only the Fold's larger text needs compact.
    @Test
    fun atFontScaleOneTheCoverScreenKeepsTheFullRing() = rail(840, 360).assertRing(RailTransport.Standard)
}

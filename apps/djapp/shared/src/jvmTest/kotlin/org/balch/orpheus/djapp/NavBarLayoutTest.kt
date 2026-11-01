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
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bar and the rail render the saved nav order: which tabs show, in what order, where the open
 * slot and the dome sit. Asserted through semantics bounds at one density unit per pixel.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*NavBarLayoutTest*'
 */
class NavBarLayoutTest {
    private val name = "Space & Drift"
    private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
    }

    private class Node(val descriptions: List<String>, val texts: List<String>, val tag: String?, val bounds: Rect)

    private class Scene(val nodes: List<Node>) {
        val tabLabels = listOf("DJ", "Mix", "Pulsar", "AI", "Timer", "Horn", "Cowbell")

        /** The tab labels present, left to right (or top to bottom). */
        fun tabs(vertical: Boolean = false): List<String> = tabLabels
            .mapNotNull { label -> nodes.firstOrNull { label in it.texts }?.let { label to it.bounds } }
            .sortedBy { (_, b) -> if (vertical) b.top else b.left }
            .map { it.first }

        fun tab(label: String): Rect = assertNotNull(nodes.firstOrNull { label in it.texts }, "no $label tab").bounds
        val openSlot: Rect? get() = nodes.firstOrNull { it.tag == NavOpenSlotTag }?.bounds
        fun dome(name: String): Rect =
            assertNotNull(nodes.firstOrNull { d -> d.descriptions.any { it.endsWith(", $name") } }, "no dome").bounds
    }

    private fun render(
        width: Int,
        height: Int,
        layout: DjLayout,
        edition: DjEdition,
        fontScale: Float = 1f,
    ): Scene {
        val order = defaultNavOrder(navPanels(edition))
        val scene = ImageComposeScene(width, height, Density(1f, fontScale)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab },
                    onItemClick = {},
                    layout = layout,
                    pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(),
                    onTogglePlayback = {},
                    navOrder = order,
                    modifier = Modifier.fillMaxSize(),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        try {
            // The bar and the rail size themselves from what they measured the frame before.
            repeat(3) { scene.render() }
            fun collect(root: SemanticsNode): List<Node> = buildList {
                fun walk(node: SemanticsNode) {
                    add(
                        Node(
                            descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
                            texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                            tag = node.config.getOrNull(SemanticsProperties.TestTag),
                            bounds = node.boundsInRoot,
                        ),
                    )
                    node.children.forEach(::walk)
                }
                walk(root)
            }
            return Scene(scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) })
        } finally {
            scene.close()
        }
    }

    @Test
    fun `AI phone at 360dp shows DJ Mix AI Timer`() {
        val bar = render(360, 780, DjLayout.Portrait, DjEdition.AI)
        assertEquals(listOf("DJ", "Mix", "AI", "Timer"), bar.tabs())
        assertNull(bar.openSlot, "a full phone bar has no open slot")
        // The dome sits between Mix and AI.
        val dome = bar.dome(name)
        assertTrue(bar.tab("Mix").right <= dome.left + 1f && dome.right - 1f <= bar.tab("AI").left, "the dome is off centre: $dome")
    }

    @Test
    fun `AI tablet at 600dp shows all six with no open slot`() {
        val bar = render(600, 900, DjLayout.Portrait, DjEdition.AI)
        assertEquals(listOf("DJ", "Mix", "AI", "Timer", "Horn", "Cowbell"), bar.tabs())
        assertNull(bar.openSlot, "six tabs fill six slots, so the bar has no open slot")
        // Equal slots either side, so the dome is dead centre of the bar.
        assertEquals(300f, bar.dome(name).center.x, 1f)
        assertTrue(bar.tab("AI").right <= bar.dome(name).left + 1f, "AI is not left of the dome")
    }

    @Test
    fun `OG phone at 600dp shows today's four plus Cowbell`() {
        val bar = render(600, 900, DjLayout.Portrait, DjEdition.OG)
        assertEquals(listOf("DJ", "Mix", "Horn", "Timer", "Cowbell"), bar.tabs())
    }

    @Test
    fun `AI rail at 412dp tall shows four tabs`() {
        // The user's font scale: its taller labels leave the compact rail room for four of five.
        val rail = render(915, 412, DjLayout.Landscape, DjEdition.AI, fontScale = 1.3f)
        assertEquals(listOf("DJ", "Mix", "AI", "Timer"), rail.tabs(vertical = true))
    }

    @Test
    fun `AI rail tall enough shows five`() {
        val labelLine = 16f
        val height = railMinHeight(RailTransport.Compact, 5, labelLine.dp).value.toInt() + 1
        val rail = render(915, height, DjLayout.Landscape, DjEdition.AI)
        assertEquals(listOf("DJ", "Mix", "AI", "Timer", "Horn"), rail.tabs(vertical = true))
    }

    @Test
    fun `AI rail a dp short of five shows four`() {
        val height = railMinHeight(RailTransport.Compact, 5, 16.dp).value.toInt() - 1
        val rail = render(915, height, DjLayout.Landscape, DjEdition.AI)
        assertEquals(listOf("DJ", "Mix", "AI", "Timer"), rail.tabs(vertical = true))
    }
}

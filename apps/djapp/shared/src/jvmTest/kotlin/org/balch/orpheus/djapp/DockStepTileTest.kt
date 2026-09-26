package org.balch.orpheus.djapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dock's ◀ and ▶ tiles: one line each, the arrow at the bar's outer end and the neighbour's
 * name beside it, its baseline on the toggles' label line (task 28C); past the restart threshold ◀
 * shows the restart icon and the current vibe.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockStepTileTest*' --rerun
 */
class DockStepTileTest {
    private val nav = VibeNavState("Rust Belt", "Dog House", "Stay Asleep", progress = 0.2f)

    private class Bar(nav: VibeNavState, tv: Boolean = false, width: Int = 1280) {
        private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
        }
        val scene = ImageComposeScene(width, 200, Density(1f)) {
            OrpheusTheme {
                CompositionLocalProvider(LocalTelevisionHardware provides tv) {
                    DjTvBottomBar(
                        panels = bottomBarPanels(largeScreenPanels()),
                        isDocked = { false },
                        onToggle = {},
                        timerFeature = TimerViewModel.previewFeature(),
                        pulsarFeature = pulsar,
                        onTogglePlayback = {},
                    )
                }
            }
        }

        init {
            // The slots' widths arrive a frame after the first layout.
            repeat(2) { scene.render() }
        }

        private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
        private val merged get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
        private val unmerged get() = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }

        /** A tile's clickable node, by its description's start. */
        fun tile(description: String): SemanticsNode = assertNotNull(
            merged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(description) } },
            "no \"$description\" tile",
        )

        /** The arrow icon alone, by the same description its tile carries -- only the icon has it unmerged. */
        fun arrow(description: String): SemanticsNode = assertNotNull(
            unmerged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(description) } },
            "no \"$description\" arrow",
        )

        /** A toggle's clickable node, by its label. */
        fun toggle(label: String): SemanticsNode = assertNotNull(
            merged.firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            },
            "no \"$label\" toggle",
        )

        fun text(label: String): SemanticsNode = assertNotNull(
            unmerged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } },
            "no \"$label\" text",
        )

        fun hasText(label: String) = unmerged.any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }

        /** Where [label]'s first line sits, in the scene's px. */
        fun baseline(label: String): Float {
            val node = text(label)
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            return node.positionInRoot.y + results.single().firstBaseline
        }

        fun pixels(): PixelMap = Image.makeFromEncoded(scene.render().encodeToData()!!.bytes).toComposeImageBitmap().toPixelMap()

        fun close() = scene.close()
    }

    private val SemanticsNode.bounds: Rect get() = Rect(positionInRoot, size.toSize())

    @Test
    fun eachTileIsOneLineWithItsArrowAtTheOuterEnd() {
        val bar = Bar(nav)
        try {
            val previous = bar.tile("Previous vibe").bounds
            val next = bar.tile("Next vibe").bounds
            val dog = bar.text("Dog House").bounds
            val stay = bar.text("Stay Asleep").bounds
            assertTrue(dog.left >= bar.arrow("Previous vibe").bounds.right, "the ◀ arrow is not first")
            assertTrue(stay.right <= bar.arrow("Next vibe").bounds.left, "the ▶ arrow is not last")
            assertTrue(previous.left < 30f && next.right > 1250f, "the tiles are not at the bar's ends: $previous, $next")
            // No caption line any more.
            listOf("Previous", "Up next", "Restart").forEach { assertTrue(!bar.hasText(it), "a \"$it\" caption is still there") }
        } finally {
            bar.close()
        }
    }

    // Task 28C: the tile's name shares the toggles' label line, and the arrow centres on it -- with
    // or without a name to show -- while the tile's own box keeps the toggles' top and height.
    @Test
    fun eachTileNameSharesTheTogglesLabelLineAndTheArrowCentresOnIt() {
        val bar = Bar(nav)
        try {
            val djBaseline = bar.baseline("DJ")
            assertEquals(djBaseline, bar.baseline("Dog House"), 1f, "the ◀ name is off the toggles' label line")
            assertEquals(djBaseline, bar.baseline("Stay Asleep"), 1f, "the ▶ name is off the toggles' label line")

            val dogLine = bar.text("Dog House").bounds
            val stayLine = bar.text("Stay Asleep").bounds
            assertEquals(dogLine.center.y, bar.arrow("Previous vibe").bounds.center.y, 2f, "the ◀ arrow is off the name's line")
            assertEquals(stayLine.center.y, bar.arrow("Next vibe").bounds.center.y, 2f, "the ▶ arrow is off the name's line")

            val djToggle = bar.toggle("DJ").bounds
            val previousTile = bar.tile("Previous vibe").bounds
            assertEquals(djToggle.top, previousTile.top, 0.5f, "the tile's box moved off the toggles' top")
            assertEquals(djToggle.height, previousTile.height, 0.5f, "the tile's box is not the toggles' height")
        } finally {
            bar.close()
        }
    }

    // No neighbour to peek: the ▶ arrow alone still centres on the toggles' label line.
    @Test
    fun withNoNameTheArrowStillCentresOnTheLabelLine() {
        val bar = Bar(nav.copy(nextName = null))
        try {
            assertTrue(!bar.hasText("Stay Asleep"), "sanity: no next name is showing")
            val djLine = bar.text("DJ").bounds
            assertEquals(djLine.center.y, bar.arrow("Next vibe").bounds.center.y, 2f, "the ▶ arrow is off the label line with no name")
        } finally {
            bar.close()
        }
    }

    // Every dock, not only desktop: television hardware keeps the same baseline-shared layout.
    @Test
    fun televisionHardwareSharesTheSameLabelLine() {
        val bar = Bar(nav, tv = true)
        try {
            assertEquals(bar.baseline("DJ"), bar.baseline("Dog House"), 1f, "TV: the ◀ name is off the toggles' label line")
            assertEquals(bar.text("Dog House").bounds.center.y, bar.arrow("Previous vibe").bounds.center.y, 2f, "TV: the ◀ arrow is off the name's line")
        } finally {
            bar.close()
        }
    }

    @Test
    fun pastTheThresholdTheLeftTileShowsTheRestartIconAndTheCurrentVibe() {
        val plain = Bar(nav)
        val restart = Bar(nav.copy(previousRestarts = true))
        try {
            val tile = restart.tile("Restart")
            assertEquals(listOf("Restart Rust Belt"), tile.config.getOrNull(SemanticsProperties.ContentDescription))
            val names = tile.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertEquals(listOf("Rust Belt"), names, "the ◀ tile does not name the current vibe")
            assertTrue(!restart.hasText("Dog House"), "the previous vibe still shows")
            // The same place, a different glyph.
            val box = restart.arrow("Restart").bounds
            val a = plain.pixels()
            val b = restart.pixels()
            var differ = 0
            for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
                if (a[x, y] != b[x, y]) differ++
            }
            assertTrue(differ > 200, "the ◀ tile kept its skip arrow past the threshold ($differ px differ)")
        } finally {
            plain.close()
            restart.close()
        }
    }
}

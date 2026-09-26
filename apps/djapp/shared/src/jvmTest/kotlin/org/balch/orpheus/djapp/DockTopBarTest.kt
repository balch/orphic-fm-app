package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dock's top row: Pulsar, Info and Ends at its start, each a dock toggle as the bottom bar's
 * are, and the Vibe + Viz pickers at its end. None of them ever reach the centred title, and below
 * about 930dp wide the pickers drop their "Vibe: "/"Viz: " prefixes.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockTopBarTest*' --rerun
 */
class DockTopBarTest {

    /** The real chrome at [width] x 720, Pulsar docked; [longest], the longest vibe name and ending style. */
    private class Dock(val width: Int, longest: Boolean = true) {
        val toggled = mutableListOf<DjRoute>()
        private val pulsar: PulsarFeature = run {
            val base = PulsarViewModel.previewFeature()
            if (!longest) return@run base
            object : PulsarFeature by base {
                override val stateFlow: StateFlow<PulsarUiState> =
                    MutableStateFlow(base.stateFlow.value.copy(vibe = base.vibeList.maxBy { it.name.length }))
                override val actions: PulsarPanelActions = base.actions.copy(
                    songEndingEnabled = MutableStateFlow(true),
                    transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.CROSSFADE)),
                )
            }
        }
        val scene = ImageComposeScene(width, 720, Density(1f)) {
            OrpheusTheme {
                Box(Modifier.fillMaxSize()) {
                    DjAppTvChrome(
                        tvHardware = false,
                        domeRingSize = BarRingSize,
                        barGlass = false,
                        vizHidesPanelsWhenIdle = false,
                        focusRegion = TvFocusRegionHolder(),
                        vizFeature = VizViewModel.previewFeature(),
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        dockablePanels = largeScreenPanels(),
                        dockedPanels = listOf(PulsarTab, DjTab),
                        activeSheet = null,
                        tabs = djTabs,
                        onToggleDocked = { toggled += it },
                        onActiveSheetChange = {},
                        stage = {},
                    )
                }
            }
        }

        init {
            repeat(2) { scene.render() }
        }

        private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)

        private val merged get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }

        /** The clickable node whose merged text reads [label], or starts with it for a picker. */
        fun control(label: String): SemanticsNode = assertNotNull(
            merged.firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label || it.text.startsWith(label) }
            },
            "no \"$label\" control at ${width}dp",
        )

        /** A picker's clickable node, by the name its arrow gives a screen reader, prefix shown or not. */
        fun picker(name: String): SemanticsNode = assertNotNull(
            merged.firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null &&
                    n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Select $name" }
            },
            "no \"$name\" picker at ${width}dp",
        )

        /** Whether any text in the bar reads exactly [text]. */
        fun shows(text: String): Boolean = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
            .any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }

        /** The title's plate: its text and the plate's 20dp sides. */
        val title: Rect
            get() = assertNotNull(
                merged.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Orphic DJ" } },
                "no title",
            ).bounds.let { Rect(it.left - 20f, it.top, it.right + 20f, it.bottom) }

        /** Whether [text] laid out whole, not ellipsised. */
        fun whole(text: String): Boolean {
            val node = assertNotNull(
                scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
                    .firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } },
                "no \"$text\" text",
            )
            val results = mutableListOf<TextLayoutResult>()
            assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
            val layout = results.single()
            // Not didOverflowWidth: a softWrap=false line lays out unbounded, which always sets it.
            return !layout.isLineEllipsized(0) && layout.size.width + 0.5f >= layout.multiParagraph.maxIntrinsicWidth
        }

        fun click(node: SemanticsNode) {
            scene.sendPointerEvent(PointerEventType.Press, node.bounds.center)
            scene.sendPointerEvent(PointerEventType.Release, node.bounds.center)
            scene.render()
        }

        fun close() = scene.close()
    }

    private companion object {
        val SemanticsNode.bounds: Rect get() = Rect(positionInRoot, size.toSize())

        fun SemanticsNode.state(): String? = config.getOrNull(SemanticsProperties.StateDescription)
    }

    @Test
    fun pulsarInfoAndEndsSitAtTheStartAndEachTogglesItsPanel() {
        val dock = Dock(1280)
        try {
            val pulsar = dock.control("Pulsar")
            val info = dock.control("Info")
            val ends = dock.control("CROSSFADE")
            val title = dock.title
            // In the top bar, all at the start, before the title.
            listOf(pulsar, info, ends).forEach { assertTrue(it.bounds.bottom <= 72f, "${it.bounds} is not in the top bar") }
            assertTrue(
                pulsar.bounds.right < info.bounds.left && info.bounds.right < ends.bounds.left && ends.bounds.right < title.left,
                "Pulsar, Info, Ends and the title are out of order",
            )
            // Docked state, as the bottom bar's toggles show it.
            assertEquals("Shown", pulsar.state())
            assertEquals("Hidden", info.state())
            dock.click(pulsar)
            dock.click(info)
            dock.click(ends)
            assertEquals(listOf(PulsarTab, VibeInfoTab, EndsTab), dock.toggled, "the toggles did not dock their own panels")
            // And they left the bottom bar.
            assertTrue(dock.control("DJ").bounds.top > 400f, "sanity: DJ is not in the bottom bar")
        } finally {
            dock.close()
        }
    }

    // Ends always sits in the left group, after Pulsar and Info, at every width the dock is laid out at.
    @Test
    fun endsSitsAtTheStartAtEveryWidth() {
        listOf(900, 1032, 1280, 1920).forEach { width ->
            val dock = Dock(width, longest = false)
            try {
                val title = dock.title
                val info = dock.control("Info").bounds
                val ends = dock.control("PLAYS").bounds
                assertTrue(ends.left > info.right && ends.right <= title.left, "at ${width}dp Ends $ends is not between Info $info and the title $title")
                assertTrue(dock.whole("PLAYS"), "at ${width}dp the Ends label was cut")
            } finally {
                dock.close()
            }
        }
    }

    // Below about 930dp (the dock's 900dp floor) the pickers drop their prefixes and show values
    // alone, whole, while still naming themselves to a screen reader; at 1032dp and up, the right
    // group has the whole side to itself, so both keep their prefix and still fit whole.
    @Test
    fun thePickersDropTheirPrefixOnlyBelowTheThreshold() {
        val vibe = PulsarViewModel.previewFeature().stateFlow.value.vibe.name
        val dock900 = Dock(900, longest = false)
        try {
            listOf("Vibe: ", "Viz: ").forEach { assertTrue(!dock900.shows(it), "at 900dp a picker still shows \"$it\"") }
            assertTrue(dock900.whole(vibe), "at 900dp the Vibe picker ellipsised \"$vibe\"")
            assertTrue(dock900.whole("Off"), "at 900dp the Viz picker ellipsised its value")
            val title = dock900.title
            val pickers = listOf("Vibe", "Viz").map { dock900.picker(it).bounds }
            pickers.forEach { assertTrue(it.left >= title.right, "at 900dp the picker $it is not after the title $title") }
            assertTrue(pickers[0].right <= pickers[1].left, "at 900dp the pickers are out of order: $pickers")
        } finally {
            dock900.close()
        }
        listOf(1032, 1280, 1920).forEach { width ->
            val dock = Dock(width, longest = false)
            try {
                assertTrue(dock.shows("Vibe: ") && dock.shows("Viz: "), "at ${width}dp a picker lost its prefix")
                assertTrue(dock.whole(vibe), "at ${width}dp the Vibe picker ellipsised \"$vibe\"")
                assertTrue(dock.whole("Off"), "at ${width}dp the Viz picker ellipsised its value")
            } finally {
                dock.close()
            }
        }
    }

    // Each side is budgeted against the title's reserve, so the widest names ellipsise before
    // anything reaches the title or leaves the bar.
    @Test
    fun nothingReachesTheTitleFrom900To1920() {
        listOf(900, 1032, 1280, 1920).forEach { width ->
            val dock = Dock(width)
            try {
                val title = dock.title
                val start = listOf("Pulsar", "Info", "CROSSFADE").map { dock.control(it).bounds }
                val end = listOf("Vibe", "Viz").map { dock.picker(it).bounds }
                start.forEach { assertTrue(it.left >= 0f && it.right <= title.left, "at ${width}dp $it reaches the title $title") }
                end.forEach { assertTrue(it.left >= title.right && it.right <= width.toFloat(), "at ${width}dp $it reaches the title $title or leaves the bar") }
            } finally {
                dock.close()
            }
        }
    }
}

package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dock's bottom bar: the saved nav order with its open slot, and the remote's customizing keys
 * (hold OK for the chip row, OK on a chip to swap, a Move chip to slide the tab along the bar).
 * Drives the real [DjAppTvChrome] on a standard test dispatcher, so the held key and the chips'
 * double-press window run on virtual time.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockNavCustomizeTest*'
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
class DockNavCustomizeTest {
    private val aiOrder = restoreNavOrder(null, navPanels(DjEdition.AI))

    // Five panels leave an odd count, which is what puts an open slot on a bar.
    private val fivePanelOrder = restoreNavOrder(null, navPanels(DjEdition.AI) - CowbellTab)

    private inner class Dock(
        private val order: List<NavEntry>,
        widthDp: Int = 1280,
        withActions: Boolean = true,
    ) {
        val toggled = mutableListOf<DjRoute>()
        val swaps = mutableListOf<Pair<DjRoute, DjRoute>>()
        val exchanges = mutableListOf<Pair<NavEntry, NavEntry>>()
        val opened = mutableListOf<DjRoute>()
        val steps = mutableListOf<SkipDirection>()
        private val keys = VibeStepKeys()
        private val scheduler = TestCoroutineScheduler()
        private var now = 0L

        private val actions = NavActions(
            onChipSwap = { slot, chip -> swaps += slot to chip },
            onExchange = { a, b -> exchanges += a to b },
            onOpenOffBar = { opened += it },
        )

        val scene = ImageComposeScene(widthDp, 720, Density(1f), StandardTestDispatcher(scheduler)) {
            OrpheusTheme {
                // Stands in for the desktop window's onKeyEvent: an arrow nothing took would step the vibe.
                Box(Modifier.fillMaxSize().onKeyEvent { event -> keys.stepFor(event)?.also { steps += it } != null }) {
                    DjAppTvChrome(
                        tvHardware = true,
                        domeRingSize = TvDockDomeRingSize,
                        barGlass = false,
                        vizHidesPanelsWhenIdle = false,
                        focusRegion = TvFocusRegionHolder(),
                        vizFeature = VizViewModel.previewFeature(),
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        dockablePanels = largeScreenPanels(),
                        dockedPanels = emptyList(),
                        activeSheet = null,
                        navOrder = order,
                        navActions = actions.takeIf { withActions },
                        onToggleDocked = { toggled += it },
                        onActiveSheetChange = {},
                        stage = {},
                    )
                }
            }
        }

        fun frame() {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            scene.render(now * 1_000_000)
        }

        /** Lets [ms] pass on the coroutine clock, with the frames a started animation or focus move needs. */
        fun idle(ms: Long) {
            now += ms
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
            repeat(3) { frame() }
        }

        init {
            // The chrome measures its width a frame after the first layout; the dome takes the launch focus.
            repeat(4) { frame() }
        }

        fun down(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            frame()
        }

        fun up(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            frame()
        }

        fun press(key: Key, shift: Boolean = false) {
            down(key, shift)
            up(key, shift)
            idle(0)
        }

        /** Holds OK past the long-press timeout and lets go. */
        fun holdOk() {
            down(Key.DirectionCenter)
            idle(700)
            up(Key.DirectionCenter)
            idle(0)
        }

        private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
        private val nodes: List<SemanticsNode> get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }

        private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        /** A bar toggle by its label. */
        fun toggle(label: String): SemanticsNode? = nodes.firstOrNull { n ->
            n.positionInRoot.y > 400f && n.config.getOrNull(SemanticsProperties.TestTag) != NavChipTag &&
                n.config.getOrNull(SemanticsActions.OnClick) != null && label in n.texts()
        }

        /** The chips in the open row, left to right. */
        val chips: List<SemanticsNode>
            get() = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == NavChipTag }
                .sortedBy { it.positionInRoot.x }

        val chipLabels: List<String> get() = chips.map { it.texts().first() }

        val focusedLabel: String?
            get() = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.texts()?.firstOrNull()

        fun debug(): String = nodes.filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            .joinToString { "${it.texts()} ${it.config.getOrNull(SemanticsProperties.ContentDescription)} ${it.positionInRoot}" }

        fun bounds(label: String): Rect = Rect(assertNotNull(toggle(label), "no \"$label\" toggle").positionInRoot, toggle(label)!!.size.toSize())

        /** Walks focus from the dome onto [label]'s toggle with Tab (a scene has no D-pad traversal). */
        fun focusToggle(label: String) {
            val backwards = bounds(label).center.x < 640f
            repeat(8) {
                if (focusedLabel == label) return
                press(Key.Tab, shift = backwards)
            }
            assertEquals(label, focusedLabel, "focus never reached \"$label\": ${debug()}")
        }

        fun close() = scene.close()
    }

    // Six panels fill the TV bar's six slots, so the default AI dock has no open slot.
    @Test
    fun `AI dock shows all six with no open slot`() {
        val dock = Dock(aiOrder)
        try {
            val labels = listOf("DJ", "Mix", "Pulsar", "AI", "Horn", "Cowbell")
            val bounds = labels.map { dock.bounds(it) }
            bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.left < b.left, "the tabs are out of order: $labels") }
            assertEquals(bounds[0].left, 1280f - bounds[5].right, 2f, "the bar is off centre")
            assertTrue(bounds[2].right < 640f && bounds[3].left > 640f, "the dome is not between Pulsar and AI")
        } finally {
            dock.close()
        }
    }

    // An odd count leaves an open slot, which a TV-wide bar draws far left.
    @Test
    fun `five panels on the dock leave the open slot far left`() {
        val dock = Dock(fivePanelOrder)
        try {
            val labels = listOf("DJ", "Mix", "Pulsar", "AI", "Horn")
            val bounds = labels.map { dock.bounds(it) }
            bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.left < b.left, "the tabs are out of order: $labels") }
            // Dome centred: three slots a side, the open slot the far-left one.
            val left = bounds[0].left
            val right = 1280f - bounds[4].right
            assertEquals(right, left - (TvBottomBarMinWidth.value + TvBottomBarItemGap.value), 2f, "no open slot at the far left")
            assertTrue(bounds[1].right < 640f && bounds[2].left > 640f, "the dome is not between Mix and Pulsar")
        } finally {
            dock.close()
        }
    }

    @Test
    fun `OG dock matches today`() {
        val dock = Dock(OgNavOrder)
        try {
            val bounds = listOf("DJ", "Mix", "Pulsar", "Horn").map { dock.bounds(it) }
            assertNull(dock.toggle("AI"))
            assertEquals(bounds[0].left, 1280f - bounds[3].right, 2f, "the bar is off centre")
            assertTrue(bounds[1].right < 640f && bounds[2].left > 640f, "the dome is not between Mix and Pulsar")
        } finally {
            dock.close()
        }
    }

    @Test
    fun `short OK still toggles`() {
        val dock = Dock(aiOrder)
        try {
            dock.focusToggle("Mix")
            dock.down(Key.DirectionCenter)
            dock.idle(80)
            dock.up(Key.DirectionCenter)
            dock.idle(600)
            assertEquals(listOf<DjRoute>(MixTab), dock.toggled)
            assertTrue(dock.chips.isEmpty(), "a short OK opened the chips")
        } finally {
            dock.close()
        }
    }

    @Test
    fun `held OK opens chips with Move`() {
        val dock = Dock(aiOrder)
        try {
            dock.focusToggle("Mix")
            dock.holdOk()
            assertEquals(listOf("Mix", "Move"), dock.chipLabels)
            assertEquals("Mix", dock.focusedLabel, "focus is not on the current chip")
            assertTrue(dock.toggled.isEmpty(), "the held OK toggled the panel")
            assertTrue(dock.swaps.isEmpty() && dock.opened.isEmpty())
        } finally {
            dock.close()
        }
    }

    // A held key auto-repeats as more key downs, some of them on the chip that took the focus.
    @Test
    fun `the held OK that opened the row does not press a chip`() {
        val dock = Dock(aiOrder)
        try {
            dock.focusToggle("Mix")
            dock.down(Key.DirectionCenter)
            dock.idle(300)
            dock.down(Key.DirectionCenter)
            dock.idle(400)
            dock.down(Key.DirectionCenter)
            dock.idle(100)
            dock.up(Key.DirectionCenter)
            dock.idle(ChipDoubleTapWindowMs + 100)
            assertEquals(listOf("Mix", "Move"), dock.chipLabels, "the release pressed the current chip")
            assertTrue(dock.toggled.isEmpty() && dock.swaps.isEmpty() && dock.exchanges.isEmpty())
        } finally {
            dock.close()
        }
    }

    // A narrow window holds four tabs, so Horn is off the bar and offered as a chip.
    @Test
    fun `OK on a chip swaps it in`() {
        val dock = Dock(aiOrder, widthDp = 800)
        try {
            assertNull(dock.toggle("Horn"), "sanity: Horn fits on the bar")
            dock.focusToggle("Mix")
            dock.holdOk()
            assertEquals(listOf("Mix", "Horn", "Cowbell", "Move"), dock.chipLabels)
            dock.press(Key.DirectionRight)
            assertEquals("Horn", dock.focusedLabel)
            dock.press(Key.DirectionCenter)
            assertTrue(dock.swaps.isEmpty(), "the swap did not wait out the double-press window")
            dock.idle(ChipDoubleTapWindowMs + 100)
            assertEquals(listOf<Pair<DjRoute, DjRoute>>(MixTab to HornTab), dock.swaps)
            assertTrue(dock.opened.isEmpty())
            assertTrue(dock.chips.isEmpty(), "the row stayed open after the swap")
        } finally {
            dock.close()
        }
    }

    @Test
    fun `double press OK on a chip opens it without swapping`() {
        val dock = Dock(aiOrder, widthDp = 800)
        try {
            dock.focusToggle("Mix")
            dock.holdOk()
            dock.press(Key.DirectionRight)
            dock.press(Key.DirectionCenter)
            dock.idle(100)
            dock.press(Key.DirectionCenter)
            dock.idle(ChipDoubleTapWindowMs + 100)
            assertEquals(listOf<DjRoute>(HornTab), dock.opened)
            assertTrue(dock.swaps.isEmpty(), "the double press also swapped")
            assertTrue(dock.chips.isEmpty())
        } finally {
            dock.close()
        }
    }

    @Test
    fun `back closes the chip row`() {
        val dock = Dock(aiOrder, widthDp = 800)
        try {
            dock.focusToggle("Mix")
            dock.holdOk()
            assertTrue(dock.chips.isNotEmpty(), "sanity: the row did not open")
            dock.press(Key.Back)
            assertTrue(dock.chips.isEmpty())
            assertTrue(dock.swaps.isEmpty() && dock.opened.isEmpty() && dock.exchanges.isEmpty())
            assertEquals("Mix", dock.focusedLabel, "focus did not return to the tab")
        } finally {
            dock.close()
        }
    }

    private fun Dock.enterMove(label: String) {
        focusToggle(label)
        holdOk()
        press(Key.DirectionRight)
        assertEquals("Move", focusedLabel, "sanity: Move is not the last chip")
        press(Key.DirectionCenter)
        assertTrue(chips.isEmpty(), "Move left the row open")
    }

    @Test
    fun `move mode right exchanges with the neighbour and skips the dome`() {
        val dock = Dock(aiOrder)
        try {
            dock.enterMove("Mix")
            dock.press(Key.DirectionRight)
            assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(MixTab) to NavEntry.Panel(PulsarTab)), dock.exchanges)
            dock.press(Key.DirectionLeft)
            assertEquals(NavEntry.Panel(DjTab), dock.exchanges.last().second)
            assertTrue(dock.steps.isEmpty(), "an arrow in move mode stepped the vibe")
            assertTrue(dock.toggled.isEmpty())
        } finally {
            dock.close()
        }
    }

    @Test
    fun `move mode exchanges with the open slot when it is next`() {
        val dock = Dock(fivePanelOrder)
        try {
            dock.enterMove("DJ")
            dock.press(Key.DirectionLeft)
            assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(DjTab) to NavEntry.Open), dock.exchanges)
        } finally {
            dock.close()
        }
    }

    @Test
    fun `back leaves move mode`() {
        val dock = Dock(aiOrder)
        try {
            dock.enterMove("Mix")
            dock.press(Key.Back)
            dock.press(Key.DirectionRight)
            assertTrue(dock.exchanges.isEmpty(), "an arrow moved the tab after Back")
        } finally {
            dock.close()
        }
    }

    @Test
    fun `OK leaves move mode without toggling`() {
        val dock = Dock(aiOrder)
        try {
            dock.enterMove("Mix")
            dock.press(Key.DirectionCenter)
            dock.idle(600)
            dock.press(Key.DirectionRight)
            assertTrue(dock.exchanges.isEmpty(), "an arrow moved the tab after OK")
            assertTrue(dock.toggled.isEmpty(), "the OK that dropped the tab also toggled it")
        } finally {
            dock.close()
        }
    }

    // OG passes no actions, so a hold is just a long OK.
    @Test
    fun `without actions a held OK opens nothing`() {
        val dock = Dock(OgNavOrder, withActions = false)
        try {
            dock.focusToggle("Mix")
            dock.holdOk()
            assertTrue(dock.chips.isEmpty())
            assertEquals(1, dock.toggled.size, "a long OK should toggle once like a press")
        } finally {
            dock.close()
        }
    }
}

package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
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
import kotlin.test.assertTrue

/**
 * The bar's and the rail's tab gestures: long-press and lift opens the chip row, long-press and move
 * drags the tab onto another, a plain tap still navigates. Driven through injected touch and mouse input.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*NavChipRowTest*'
 */
@OptIn(ExperimentalTestApi::class)
class NavChipRowTest {
    private val name = "Space & Drift"
    private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState(name, "Dog House", "Stay Asleep", progress = 0.62f))
    }

    private class Recorder {
        val swaps = mutableListOf<Pair<DjRoute, DjRoute>>()
        val exchanges = mutableListOf<Pair<NavEntry, NavEntry>>()
        val opened = mutableListOf<DjRoute>()
        val clicks = mutableListOf<DjRoute>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val hapticFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }
        val actions = NavActions(
            onChipSwap = { slot, chip -> swaps += slot to chip },
            onExchange = { a, b -> exchanges += a to b },
            onOpenOffBar = { opened += it },
        )
    }

    private fun ComposeUiTest.show(
        recorder: Recorder,
        widthDp: Int = 360,
        heightDp: Int = 780,
        layout: DjLayout = DjLayout.Portrait,
        fontScale: Float = 1f,
        actions: Boolean = true,
        order: List<NavEntry> = defaultNavOrder(navPanels(DjEdition.AI)),
    ) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalHapticFeedback provides recorder.hapticFeedback,
            ) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab },
                        onItemClick = { recorder.clicks += it },
                        layout = layout,
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        navOrder = order,
                        navActions = recorder.actions.takeIf { actions },
                        modifier = Modifier.size(widthDp.dp, heightDp.dp),
                    ) { Box(Modifier.size(1.dp)) }
                }
            }
        }
        // The bar and the rail size themselves from what they measured the frame before.
        repeat(3) { waitForIdle() }
    }

    private fun ComposeUiTest.tab(label: String): SemanticsNodeInteraction =
        onNode(hasText(label) and hasClickAction() and !hasTestTag(NavChipTag))

    private fun ComposeUiTest.chip(label: String): SemanticsNodeInteraction =
        onNode(hasTestTag(NavChipTag) and hasText(label))

    private fun ComposeUiTest.dome(): SemanticsNodeInteraction = onNode(
        SemanticsMatcher("the dome") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.endsWith(", $name") } == true
        },
    )

    private fun ComposeUiTest.chipLabels(): List<String> =
        onAllNodes(hasTestTag(NavChipTag)).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        }

    /** Presses [from], holds past the long-press timeout, moves to the centre of [to] and lifts. */
    private fun ComposeUiTest.dragOnto(from: SemanticsNodeInteraction, to: SemanticsNodeInteraction) {
        val target = to.fetchSemanticsNode().boundsInRoot.center
        val origin = from.fetchSemanticsNode().boundsInRoot.topLeft
        from.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(target - origin)
            up()
        }
        waitForIdle()
    }

    @Test
    fun `long press shows current then off bar chips`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf("Timer", "Horn", "Cowbell"), chipLabels())
        chip("Timer").assertIsSelected()
    }

    @Test
    fun `the chips open at the timeout before the finger lifts`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(1f, 0f))
        }
        waitForIdle()
        assertEquals(listOf("Timer", "Horn", "Cowbell"), chipLabels(), "no chips while the finger is still down")
        tab("Timer").performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf("Timer", "Horn", "Cowbell"), chipLabels(), "lifting closed the chips")
        assertTrue(recorder.clicks.isEmpty() && recorder.exchanges.isEmpty())
    }

    @Test
    fun `a long press that drifts within the tab still opens the chips`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput {
            down(center)
            advanceEventTime(700)
            // Past touch slop but still on the tab: a finger rolling as it lifts.
            moveBy(Offset(viewConfiguration.touchSlop * 1.5f, viewConfiguration.touchSlop * 0.5f))
            up()
        }
        waitForIdle()
        assertEquals(listOf("Timer", "Horn", "Cowbell"), chipLabels())
        assertTrue(recorder.exchanges.isEmpty(), "the drift dragged: ${recorder.exchanges}")
    }

    @Test
    fun `a long press buzzes once at the timeout`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf(HapticFeedbackType.LongPress), recorder.haptics)
        tab("Mix").performClick()
        waitForIdle()
        assertEquals(listOf(HapticFeedbackType.LongPress), recorder.haptics, "a plain tap buzzed")
    }

    @Test
    fun `chip tap opens at once`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        chip("Horn").performClick()
        waitForIdle()
        assertEquals(listOf<DjRoute>(HornTab), recorder.opened)
        assertTrue(recorder.swaps.isEmpty(), "a tap also swapped")
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
    }

    @Test
    fun `chip long press swaps without opening`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        chip("Horn").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf<Pair<DjRoute, DjRoute>>(TimerTab to HornTab), recorder.swaps)
        assertTrue(recorder.opened.isEmpty(), "a long press also opened")
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
    }

    @Test
    fun `tapping the current chip closes the row with no call`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        chip("Timer").performClick()
        waitForIdle()
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
        assertTrue(recorder.swaps.isEmpty() && recorder.opened.isEmpty() && recorder.exchanges.isEmpty())
    }

    @Test
    fun `long press never fires the tab tap`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        assertTrue(recorder.clicks.isEmpty(), "the long press also tapped: ${recorder.clicks}")
    }

    @Test
    fun `a plain tap still navigates`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Mix").performClick()
        waitForIdle()
        assertEquals(listOf<DjRoute>(MixTab), recorder.clicks)
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
    }

    @Test
    fun `drag onto a tab exchanges`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        dragOnto(tab("DJ"), tab("Timer"))
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(DjTab) to NavEntry.Panel(TimerTab)), recorder.exchanges)
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
        assertTrue(recorder.clicks.isEmpty(), "the drag also tapped: ${recorder.clicks}")
    }

    private fun ComposeUiTest.centre(label: String): Offset = tab(label).fetchSemanticsNode().boundsInRoot.center

    /**
     * Long-presses [from] and moves the finger to [to] in root coordinates, leaving it down. Later moves
     * go through [moveFinger]: [from]'s own node is drawn under the finger, so its coordinates drift.
     * Use a bar with no chips: here the chip popup takes the moves until the finger lifts.
     */
    private fun ComposeUiTest.holdOver(from: SemanticsNodeInteraction, to: Offset) {
        from.performTouchInput { down(center) }
        mainClock.advanceTimeBy(700)
        moveFinger(to)
    }

    private fun ComposeUiTest.moveFinger(to: Offset) {
        onRoot().performTouchInput { moveTo(to) }
        waitForIdle()
    }

    private fun ComposeUiTest.liftFinger() {
        onRoot().performTouchInput { up() }
        waitForIdle()
    }

    @Test
    fun `dragging over a tab slides it into the dragged tab's place`() = runComposeUiTest {
        val recorder = Recorder()
        // Six tabs fit at 600dp, so no chip row opens.
        show(recorder, widthDp = 600)
        val djHome = centre("DJ")
        val timerHome = centre("Timer")
        holdOver(tab("DJ"), timerHome)
        assertEquals(djHome.x, centre("Timer").x, 1f, "Timer did not preview the swap")
        liftFinger()
        // The recorder keeps the order, so the dropped-on tab is back in its own slot.
        assertEquals(timerHome.x, centre("Timer").x, 1f, "Timer kept its preview after the drop")
    }

    @Test
    fun `a dragged tab leaving another sends it home`() = runComposeUiTest {
        val recorder = Recorder()
        // Six tabs fit at 600dp, so no chip row opens.
        show(recorder, widthDp = 600)
        val timerHome = centre("Timer")
        holdOver(tab("DJ"), timerHome)
        moveFinger(dome().fetchSemanticsNode().boundsInRoot.center)
        assertEquals(timerHome.x, centre("Timer").x, 1f, "Timer stayed displaced off the drag")
        liftFinger()
        assertTrue(recorder.exchanges.isEmpty(), "dropped on the dome: ${recorder.exchanges}")
        // A cancelled drop is silent.
        assertEquals(listOf(HapticFeedbackType.LongPress, HapticFeedbackType.SegmentTick), recorder.haptics)
    }

    @Test
    fun `a dragged tab ticks over each tab and confirms the drop`() = runComposeUiTest {
        val recorder = Recorder()
        // Six tabs fit at 600dp, so no chip row opens.
        show(recorder, widthDp = 600)
        val dome = dome().fetchSemanticsNode().boundsInRoot.center
        val ai = centre("AI")
        holdOver(tab("DJ"), centre("Timer"))
        moveFinger(dome)
        moveFinger(ai)
        liftFinger()
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(DjTab) to NavEntry.Panel(AiTab)), recorder.exchanges)
        assertEquals(
            listOf(
                HapticFeedbackType.LongPress,
                HapticFeedbackType.SegmentTick,
                HapticFeedbackType.SegmentTick,
                HapticFeedbackType.Confirm,
            ),
            recorder.haptics,
        )
    }

    @Test
    fun `drag onto the dome cancels`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        dragOnto(tab("DJ"), dome())
        assertTrue(recorder.exchanges.isEmpty(), "dropped on the dome: ${recorder.exchanges}")
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
    }

    @Test
    fun `a full bar absent means long press does nothing`() = runComposeUiTest {
        val recorder = Recorder()
        // The six-panel AI order fills a 600dp bar, so the open slot needs the five-panel order.
        show(recorder, widthDp = 600, order = defaultNavOrder(navPanels(DjEdition.AI) - CowbellTab))
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
        dragOnto(tab("Horn"), onNode(hasTestTag(NavOpenSlotTag)))
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(HornTab) to NavEntry.Open), recorder.exchanges)
    }

    @Test
    fun `OG bar ignores long press`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, actions = false)
        tab("Timer").performTouchInput { longClick() }
        waitForIdle()
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
        dragOnto(tab("DJ"), tab("Timer"))
        assertTrue(recorder.exchanges.isEmpty())
    }

    @Test
    fun `a right click opens the chips`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder)
        tab("Timer").performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(listOf("Timer", "Horn", "Cowbell"), chipLabels())
        assertTrue(recorder.clicks.isEmpty(), "the right click also tapped: ${recorder.clicks}")
    }

    @Test
    fun `rail long press shows the chips and a drag exchanges vertically`() = runComposeUiTest {
        val recorder = Recorder()
        // The user's font scale: the compact rail has room for four of the six.
        show(recorder, widthDp = 915, heightDp = 412, layout = DjLayout.Landscape, fontScale = 1.3f)
        tab("Mix").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf("Mix", "Horn", "Cowbell"), chipLabels())
        chip("Mix").performClick()
        waitForIdle()
        dragOnto(tab("DJ"), tab("AI"))
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(DjTab) to NavEntry.Panel(AiTab)), recorder.exchanges)
    }
}

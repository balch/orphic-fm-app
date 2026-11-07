package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dock bar under a mouse, as on the desktop: the same long press, drag and right click as the
 * phone bar and the rail (see [NavChipRowTest]); the remote's keys are in [DockNavCustomizeTest].
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockNavPointerTest*'
 */
@OptIn(ExperimentalTestApi::class)
class DockNavPointerTest {
    private val aiOrder = defaultNavOrder(navPanels(DjEdition.AI))
    private val drops = navDrops(DjLayout.LargeScreen)

    private class Recorder {
        val toggled = mutableListOf<DjRoute>()
        val swaps = mutableListOf<Pair<DjRoute, DjRoute>>()
        val opened = mutableListOf<DjRoute>()
        val exchanges = mutableListOf<Pair<NavEntry, NavEntry>>()
        val actions = NavActions(
            onChipSwap = { slot, chip -> swaps += slot to chip },
            onExchange = { a, b -> exchanges += a to b },
            onOpenOffBar = { opened += it },
        )
    }

    private fun ComposeUiTest.show(
        recorder: Recorder,
        nav: NavBar,
        actions: Boolean = true,
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OrpheusTheme {
                    DjTvBottomBar(
                        nav = nav,
                        navActions = recorder.actions.takeIf { actions },
                        isDocked = { false },
                        onToggle = { recorder.toggled += it },
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.width(1280.dp),
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.tab(label: String): SemanticsNodeInteraction =
        onNode(hasText(label) and hasClickAction() and !hasTestTag(NavChipTag))

    private fun ComposeUiTest.chipLabels(): List<String> =
        onAllNodes(hasTestTag(NavChipTag)).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        }

    /** Presses [from], holds past the long-press timeout, moves to the centre of [to] and releases. */
    private fun ComposeUiTest.mouseDragOnto(from: SemanticsNodeInteraction, to: SemanticsNodeInteraction) {
        val target = to.fetchSemanticsNode().boundsInRoot.center
        val origin = from.fetchSemanticsNode().boundsInRoot.topLeft
        from.performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(700)
            moveTo(target - origin)
            release()
        }
        waitForIdle()
    }

    @Test
    fun `a right click opens the chips without toggling`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, navBar(aiOrder, drops, maxEvenSlots = 4))
        tab("Mix").performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(listOf("Mix", "Horn", "Cowbell"), chipLabels())
        assertTrue(recorder.toggled.isEmpty(), "the right click also toggled: ${recorder.toggled}")
    }

    @Test
    fun `a held press opens the chips and a chip opens its panel`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, navBar(aiOrder, drops, maxEvenSlots = 4))
        tab("Mix").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(700)
            release()
        }
        waitForIdle()
        assertEquals(listOf("Mix", "Horn", "Cowbell"), chipLabels())
        assertTrue(recorder.toggled.isEmpty(), "the held press also toggled: ${recorder.toggled}")
        onNode(hasTestTag(NavChipTag) and hasText("Horn")).performClick()
        waitForIdle()
        assertEquals(listOf<DjRoute>(HornTab), recorder.opened)
    }

    @Test
    fun `dragging a tab onto another exchanges them`() = runComposeUiTest {
        val recorder = Recorder()
        // The fullscreen bar: all six fit, so no chips open.
        show(recorder, navBar(aiOrder, drops, TvBarMaxSlots))
        mouseDragOnto(tab("DJ"), tab("Cowbell"))
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(DjTab) to NavEntry.Panel(CowbellTab)), recorder.exchanges)
        assertTrue(recorder.toggled.isEmpty(), "the drag also toggled: ${recorder.toggled}")
    }

    @Test
    fun `dragging a tab onto the open slot exchanges them`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, OgTvNavBar)
        mouseDragOnto(tab("Cowbell"), onNode(hasTestTag(NavOpenSlotTag)))
        assertEquals(listOf<Pair<NavEntry, NavEntry>>(NavEntry.Panel(CowbellTab) to NavEntry.Open), recorder.exchanges)
    }

    private fun positionFor(slotCenterX: Int, popupWidth: Int): IntOffset {
        val bar = IntRect(0, 800, 1600, 1000)
        val slot = IntRect(slotCenterX - 50, 810, slotCenterX + 50, 990)
        return ChipRowPositioner(NavChipRowState(MixTab, listOf(HornTab), bar, slot), vertical = false, gapPx = 8)
            .calculatePosition(IntRect.Zero, IntSize(1600, 1000), LayoutDirection.Ltr, IntSize(popupWidth, 60))
    }

    @Test
    fun `the chip row sits above the pressed tab`() {
        assertEquals(IntOffset(1000 - 150, 800 - 60 - 8), positionFor(1000, popupWidth = 300))
    }

    @Test
    fun `the chip row stays in the window at the bar's ends`() {
        assertEquals(0, positionFor(50, popupWidth = 300).x)
        assertEquals(1600 - 300, positionFor(1550, popupWidth = 300).x)
    }

    @Test
    fun `the chip row is only as wide as its chips`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, navBar(aiOrder, drops, maxEvenSlots = 4))
        tab("Mix").performMouseInput { rightClick() }
        waitForIdle()
        val chips = onAllNodes(hasTestTag(NavChipTag)).fetchSemanticsNodes().map { it.boundsInWindow }
        val span = chips.maxOf { it.right } - chips.minOf { it.left }
        assertTrue(span < 640f, "three chips span $span of the 1280 bar")
        val mixX = tab("Mix").fetchSemanticsNode().boundsInWindow.center.x
        assertTrue(mixX in (chips.minOf { it.left } - 16f)..(chips.maxOf { it.right } + 16f), "the row left Mix at $mixX: $chips")
    }

    @Test
    fun `a plain click still toggles`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, navBar(aiOrder, drops, TvBarMaxSlots))
        tab("Mix").performClick()
        waitForIdle()
        assertEquals(listOf<DjRoute>(MixTab), recorder.toggled)
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
    }

    @Test
    fun `without actions the pointer gestures do nothing`() = runComposeUiTest {
        val recorder = Recorder()
        show(recorder, navBar(aiOrder, drops, maxEvenSlots = 4), actions = false)
        tab("Mix").performMouseInput { rightClick() }
        waitForIdle()
        onAllNodes(hasTestTag(NavChipTag)).assertCountEquals(0)
        mouseDragOnto(tab("DJ"), tab("Mix"))
        assertTrue(recorder.exchanges.isEmpty())
    }
}

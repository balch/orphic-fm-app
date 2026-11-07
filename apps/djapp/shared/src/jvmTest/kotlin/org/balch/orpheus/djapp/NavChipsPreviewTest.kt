package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.onboarding.OnboardingSequencer
import org.balch.orpheus.core.onboarding.OnboardingStep
import org.balch.orpheus.core.preferences.OnboardingProgress
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bar teaching its long press: the first launch's pretend press and both callouts, the reminder
 * on later launches, and the dome's wiggle sitting out any launch they show on.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*NavChipsPreviewTest*'
 */
@OptIn(ExperimentalTestApi::class)
class NavChipsPreviewTest {
    private val drops = navDrops(DjLayout.LargeScreen)
    /** Four slots of the OG order: DJ, Mix, Pulsar and Horn on the bar, Cowbell off it. */
    private val hornBar = navBar(defaultNavOrder(navPanels(DjEdition.OG)), drops, 4)
    /** The OG order with room for every tab: nothing off the bar to show. */
    private val fullBar = navBar(defaultNavOrder(navPanels(DjEdition.OG)), drops, TvBarMaxSlots)

    private val rowHint = navChipRowHint(desktop = true)

    private fun launchOf(preferences: MemoryPreferences) =
        OnboardingSequencer(djOnboardingQueue(longPressTour = true), preferences, CoroutineScope(Dispatchers.Unconfined))

    private fun tourlessLaunchOf(preferences: MemoryPreferences) =
        OnboardingSequencer(djOnboardingQueue(longPressTour = false), preferences, CoroutineScope(Dispatchers.Unconfined))

    private fun preferencesWith(vararg progress: Pair<OnboardingStep, OnboardingProgress>) =
        MemoryPreferences().apply {
            prefs = prefs.copy(onboarding = progress.associate { (step, saved) -> step.id to saved })
        }

    private fun ComposeUiTest.show(
        onboarding: OnboardingSequencer,
        nav: NavBar,
        tv: Boolean = false,
        wiggleTurn: CompletableDeferred<Int?>? = null,
    ) {
        // An auto-advancing clock would run the press through to its end at every waitForIdle.
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalOnboarding provides onboarding,
                LocalTelevisionHardware provides tv,
            ) {
                OrpheusTheme {
                    DjTvBottomBar(
                        nav = nav,
                        navActions = NavActions({ _, _ -> }, { _, _ -> }, {}),
                        isDocked = { false },
                        onToggle = {},
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.width(1280.dp),
                    )
                }
                if (wiggleTurn != null) {
                    LaunchedEffect(Unit) { wiggleTurn.complete(onboarding.awaitTurn(DomeSwipeStep)) }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.chipLabels(): List<String> =
        onAllNodes(hasTestTag(NavChipTag)).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        }

    private fun ComposeUiTest.showing(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.advance(millis: Long) {
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    /** From the bar showing to the pretend press landing, partway through its rise. */
    private val toTheLift = NavChipsPreviewDelayMillis + NavChipsPreviewPressMillis + 100

    /** From there until the tab has settled. */
    private val toTheSettle = NavChipsPreviewLiftMillis + NavChipsPreviewHoldMillis + NavChipsPreviewSettleMillis + 100

    private fun ComposeUiTest.tapElsewhere() {
        onNode(hasText("DJ") and hasClickAction() and !hasTestTag(NavChipTag)).performClick()
        waitForIdle()
    }

    private fun MemoryPreferences.progress(step: OnboardingStep) = prefs.onboarding[step.id]

    // ==================== the first launch ====================

    @Test
    fun `a first launch presses Horn under a callout and hands over to the row's callout`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        show(launchOf(preferences), hornBar)
        assertEquals(emptyList(), chipLabels(), "the row opened before the beat")
        assertFalse(showing(NavLongPressHint), "the callout came before the beat")

        advance(NavChipsPreviewDelayMillis + NavChipsPreviewPressMillis / 2)
        assertTrue(showing(NavLongPressHint), "the press had no callout")
        assertEquals(emptyList(), chipLabels(), "the row opened mid-press")

        advance(NavChipsPreviewPressMillis / 2 + 100)
        assertEquals(listOf("Horn", "Cowbell"), chipLabels())
        assertTrue(showing(rowHint), "the row had no callout")

        advance(toTheSettle)
        assertFalse(showing(NavLongPressHint), "the press's callout outlived it")
        assertEquals(1, preferences.progress(NavLongPressStep)?.shows, "the press was not remembered")

        // Still open with the pretend finger gone; a tap elsewhere closes it, as ever.
        advance(3_000)
        assertEquals(listOf("Horn", "Cowbell"), chipLabels(), "the row closed on its own")
        assertTrue(showing(rowHint), "the row's callout left before the row")
        tapElsewhere()
        assertEquals(emptyList(), chipLabels(), "a tap elsewhere left the row open")
        assertFalse(showing(rowHint))
    }

    @Test
    fun `the press takes the launch from the wiggle`() = runComposeUiTest {
        val turn = CompletableDeferred<Int?>()
        show(launchOf(MemoryPreferences()), hornBar, wiggleTurn = turn)
        assertFalse(turn.isCompleted, "the wiggle went before the bar decided")
        advance(NavChipsPreviewDelayMillis + 100)
        assertNull(turn.getCompleted(), "the wiggle kept a launch the press took")
        advance(toTheLift + toTheSettle)
        tapElsewhere()
        assertNull(turn.getCompleted(), "closing the row gave the wiggle the launch back")
    }

    @Test
    fun `picking a chip teaches the row and leaves the press alone`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        show(launchOf(preferences), hornBar)
        advance(toTheLift + toTheSettle)
        onNode(hasText("Cowbell") and hasTestTag(NavChipTag)).performClick()
        advance(100)
        assertEquals(true, preferences.progress(NavChipRowStep)?.learned)
        assertNull(preferences.progress(NavLongPressStep)?.learned?.takeIf { it }, "a chip taught the long press")
    }

    @Test
    fun `a learned row opens without its callout`() = runComposeUiTest {
        show(launchOf(preferencesWith(NavChipRowStep to OnboardingProgress(learned = true))), hornBar)
        advance(toTheLift)
        assertEquals(listOf("Horn", "Cowbell"), chipLabels())
        assertFalse(showing(rowHint), "a learned row kept its callout")
    }

    // ==================== later launches ====================

    @Test
    fun `a later launch shows the callout alone and counts it`() = runComposeUiTest {
        val preferences = preferencesWith(NavLongPressStep to OnboardingProgress(shows = 1))
        val turn = CompletableDeferred<Int?>()
        show(launchOf(preferences), hornBar, wiggleTurn = turn)
        advance(NavChipsPreviewDelayMillis + 500)
        assertNull(turn.getCompleted(), "the wiggle kept a launch the reminder took")
        assertTrue(showing(NavLongPressHint), "the reminder never came")
        assertEquals(emptyList(), chipLabels(), "a reminder opened the row")
        assertEquals(2, preferences.progress(NavLongPressStep)?.shows)

        advance(NavLongPressHintMillis + NavCalloutClearMillis)
        assertFalse(showing(NavLongPressHint), "the reminder stayed")
        assertEquals(emptyList(), chipLabels())
    }

    @Test
    fun `the reminder stops at its cap`() = runComposeUiTest {
        val preferences = preferencesWith(NavLongPressStep to OnboardingProgress(shows = NavLongPressStep.maxShows))
        val turn = CompletableDeferred<Int?>()
        show(launchOf(preferences), hornBar, wiggleTurn = turn)
        advance(NavChipsPreviewDelayMillis + 500)
        assertFalse(showing(NavLongPressHint), "the reminder ran past its cap")
        assertEquals(0, turn.getCompleted(), "the wiggle stayed off once the reminders ended")
    }

    @Test
    fun `a real long press teaches it and ends the reminder`() = runComposeUiTest {
        val preferences = preferencesWith(NavLongPressStep to OnboardingProgress(shows = 1))
        show(launchOf(preferences), hornBar)
        advance(NavChipsPreviewDelayMillis + 500)
        assertTrue(showing(NavLongPressHint), "sanity: the reminder never came")

        onNode(hasText("Horn") and hasClickAction()).performTouchInput { longClick() }
        waitForIdle()
        assertEquals(true, preferences.progress(NavLongPressStep)?.learned, "the press was not learned")
        assertEquals(listOf("Horn", "Cowbell"), chipLabels())

        tapElsewhere()
        advance(NavLongPressHintMillis + NavCalloutClearMillis)
        assertFalse(showing(NavLongPressHint), "the reminder outlived its time")
    }

    @Test
    fun `a learned long press never reminds`() = runComposeUiTest {
        val preferences = preferencesWith(NavLongPressStep to OnboardingProgress(shows = 1, learned = true))
        val turn = CompletableDeferred<Int?>()
        show(launchOf(preferences), hornBar, wiggleTurn = turn)
        advance(NavChipsPreviewDelayMillis + 500)
        assertFalse(showing(NavLongPressHint))
        assertEquals(0, turn.getCompleted(), "learning the press never freed the wiggle")
    }

    // ==================== launch by launch ====================

    /** One launch: the long press shows if it is due, and the wiggle says if it still has this launch. */
    private suspend fun launchShows(preferences: MemoryPreferences): Pair<Int?, Int?> {
        val onboarding = launchOf(preferences)
        val press = onboarding.awaitTurn(NavLongPressStep)
        if (press != null) onboarding.run(NavLongPressStep) {}
        return press to onboarding.awaitTurn(DomeSwipeStep)
    }

    @Test
    fun `the press then one reminder then the wiggle from the third launch`() = runTest {
        val preferences = MemoryPreferences()
        assertEquals(0 to null, launchShows(preferences), "the first launch")
        assertEquals(1 to null, launchShows(preferences), "the second launch")
        assertEquals(null to 0, launchShows(preferences), "the third launch")
        assertEquals(null to 0, launchShows(preferences), "the wiggle stopped after one launch")
    }

    @Test
    fun `a long press on the first launch means no reminder on the second`() = runTest {
        val preferences = MemoryPreferences()
        val first = launchOf(preferences)
        first.run(NavLongPressStep) {}
        first.learn(NavLongPressStep)
        val press = launchShows(preferences).first
        assertNull(press, "the reminder came after a real long press")
    }

    // ==================== the tour switched off ====================

    @Test
    fun `without the tour a first launch wiggles at once and presses nothing`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        val turn = CompletableDeferred<Int?>()
        show(tourlessLaunchOf(preferences), hornBar, wiggleTurn = turn)
        advance(100)
        assertEquals(0, turn.getCompleted(), "the wiggle was held for a press that never comes")
        advance(toTheLift + toTheSettle)
        assertEquals(emptyList(), chipLabels())
        assertFalse(showing(NavLongPressHint))
        assertNull(preferences.progress(NavLongPressStep), "a tour that never ran was counted")
    }

    @Test
    fun `without the tour no later launch reminds`() = runComposeUiTest {
        val preferences = preferencesWith(NavLongPressStep to OnboardingProgress(shows = 1))
        val turn = CompletableDeferred<Int?>()
        show(tourlessLaunchOf(preferences), hornBar, wiggleTurn = turn)
        advance(100)
        assertEquals(0, turn.getCompleted(), "the wiggle sat out a launch with no reminder")
        advance(NavChipsPreviewDelayMillis + 500)
        assertFalse(showing(NavLongPressHint))
    }

    @Test
    fun `without the tour a real long press still gets the row's callout`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        show(tourlessLaunchOf(preferences), hornBar)
        onNode(hasText("Horn") and hasClickAction()).performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf("Horn", "Cowbell"), chipLabels())
        assertTrue(showing(rowHint), "the row lost its callout")
        assertEquals(true, preferences.progress(NavLongPressStep)?.learned, "turning the tour on later would teach it again")
    }

    // ==================== nothing to teach ====================

    @Test
    fun `a bar with every tab on it gives the launch up uncounted and frees the wiggle`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        val turn = CompletableDeferred<Int?>()
        show(launchOf(preferences), fullBar, wiggleTurn = turn)
        advance(NavChipsPreviewDelayMillis + NavChipsPreviewLayoutWaitMillis + 100)
        assertEquals(emptyList(), chipLabels())
        assertFalse(showing(NavLongPressHint))
        assertNull(preferences.progress(NavLongPressStep), "a press that showed nothing was spent")
        assertEquals(0, turn.getCompleted(), "the wiggle was still held")
    }

    @Test
    fun `a television shows nothing and spends nothing`() = runComposeUiTest {
        val preferences = MemoryPreferences()
        show(launchOf(preferences), hornBar, tv = true)
        advance(toTheLift + toTheSettle)
        assertEquals(emptyList(), chipLabels())
        assertFalse(showing(NavLongPressHint))
        assertNull(preferences.progress(NavLongPressStep))
    }

    @Test
    fun `a television frees the wiggle at once`() = runComposeUiTest {
        val turn = CompletableDeferred<Int?>()
        show(launchOf(MemoryPreferences()), hornBar, tv = true, wiggleTurn = turn)
        advance(100)
        assertEquals(0, turn.getCompleted(), "the wiggle was still held")
    }

    /**
     * Whether a caret is drawn at [x] under the callout whose [text] bounds are given. At density 1 the
     * bubble ends 6 below its text and the caret runs 7 below that; this looks 3 into the caret.
     */
    private fun ComposeUiTest.caretAt(text: Rect, x: Float): Boolean {
        val pixels = onAllNodes(isRoot()).onFirst().captureToImage().toPixelMap()
        val color = pixels[x.roundToInt(), text.bottom.roundToInt() + 9]
        return color.red < 0.2f && color.green > 0.8f && color.blue > 0.8f
    }

    @Test
    fun `the row's caret points at the first chip it can pick`() = runComposeUiTest {
        show(launchOf(MemoryPreferences()), hornBar)
        advance(toTheLift + toTheSettle)
        val chip = onAllNodes(hasTestTag(NavChipTag)).fetchSemanticsNodes()[1].boundsInWindow
        val callout = onNode(hasText(rowHint)).fetchSemanticsNode().boundsInWindow
        assertTrue(callout.bottom <= chip.top, "the callout covers the chip")
        assertTrue(caretAt(callout, chip.center.x), "no caret over the chip")
        assertFalse(caretAt(callout, chip.center.x + 20), "the caret isn't where it was aimed")
    }

    @Test
    fun `the press's caret points at the tab`() = runComposeUiTest {
        show(launchOf(MemoryPreferences()), hornBar)
        advance(NavChipsPreviewDelayMillis + NavChipsPreviewPressMillis - 50)
        val tab = onNode(hasText("Horn")).fetchSemanticsNode().boundsInWindow
        val callout = onNode(hasText(NavLongPressHint)).fetchSemanticsNode().boundsInWindow
        assertTrue(caretAt(callout, tab.center.x), "no caret over the tab")
        assertFalse(caretAt(callout, tab.center.x + 20), "the caret isn't where it was aimed")
    }

    @Test
    fun `a callout centres over its chip until the row runs out`() {
        assertEquals(100, chipCalloutLeft(chipCentre = 200f, calloutWidth = 200, columnWidth = 500))
        assertEquals(0, chipCalloutLeft(chipCentre = 40f, calloutWidth = 200, columnWidth = 500))
        assertEquals(300, chipCalloutLeft(chipCentre = 480f, calloutWidth = 200, columnWidth = 500))
        assertEquals(150, chipCalloutLeft(chipCentre = null, calloutWidth = 200, columnWidth = 500))
        assertEquals(0, chipCalloutLeft(chipCentre = 200f, calloutWidth = 600, columnWidth = 500))
    }

    private fun placed(slot: IntRect, vertical: Boolean, popup: IntSize) =
        ChipRowPositioner(NavChipRowState(HornTab, emptyList(), IntRect(0, 700, 400, 800), slot), vertical, 4).also {
            it.calculatePosition(IntRect.Zero, IntSize(400, 800), LayoutDirection.Ltr, popup)
        }

    @Test
    fun `a tab's caret still finds it when the callout is pushed off the edge`() {
        val middle = placed(IntRect(150, 710, 250, 790), vertical = false, popup = IntSize(120, 40))
        assertEquals(60f, middle.tabCentreAlong)
        val atTheEdge = placed(IntRect(320, 710, 400, 790), vertical = false, popup = IntSize(120, 40))
        assertEquals(80f, atTheEdge.tabCentreAlong)
    }

    @Test
    fun `a rail tab's caret follows its height`() {
        val tab = placed(IntRect(0, 20, 80, 60), vertical = true, popup = IntSize(120, 80))
        assertEquals(40f, tab.tabCentreAlong)
    }

    @Test
    fun `the preview presses Horn where it is on the bar and the last tab otherwise`() {
        assertEquals(HornTab, navChipsPreviewTab(hornBar.panels))
        val aiBar = navBar(defaultNavOrder(navPanels(DjEdition.AI)), drops, 4)
        assertEquals(AiTab, navChipsPreviewTab(aiBar.panels))
        assertEquals(null, navChipsPreviewTab(emptyList()))
    }

    @Test
    fun `the row's callout speaks for the pointer in hand`() {
        assertEquals("Tap to open, long press to replace", navChipRowHint(desktop = false))
        assertEquals("Click to open, long press to replace", navChipRowHint(desktop = true))
    }
}

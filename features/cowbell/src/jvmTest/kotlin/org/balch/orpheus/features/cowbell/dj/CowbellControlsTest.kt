package org.balch.orpheus.features.cowbell.dj

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.balch.orpheus.features.cowbell.CowbellPanelActions
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.features.cowbell.laidOut
import org.balch.orpheus.features.cowbell.text
import org.balch.orpheus.features.cowbell.textOverflows
import org.balch.orpheus.features.cowbell.textWidthShortfall
import org.balch.orpheus.features.cowbell.walk
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The DJ cowbell's controls column: the mode dropdown, the chips and the press-and-slide slider.
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellControlsTest*'
 */
@OptIn(ExperimentalTestApi::class)
class CowbellControlsTest {

    private class Calls {
        val modes = mutableListOf<CowbellMode>()
        val engines = mutableListOf<Int>()
        val octaves = mutableListOf<Int>()
        val levels = mutableListOf<Float>()
        val mores = mutableListOf<Float>()
        val grids = mutableListOf<Boolean>()
        val locks = mutableListOf<Float>()
        val bendScales = mutableListOf<Boolean>()
        val motions = mutableListOf<Boolean>()
        var modeMenuOpens = 0
        val fills = mutableListOf<CowbellFill>()
        val haptics = mutableListOf<HapticFeedbackType>()
        var releases = 0

        val hapticFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }

        val actions = CowbellPanelActions.EMPTY.copy(
            onMode = { modes += it },
            onEngine = { engines += it },
            onOctave = { octaves += it },
            onLevel = { levels += it },
            onMore = { mores += it },
            onGrid = { grids += it },
            onLock = { locks += it },
            onBendScale = { bendScales += it },
            onMotion = { motions += it },
            onModeMenuOpened = { modeMenuOpens++ },
            onFill = { fills += it },
            releaseAll = { releases++ },
        )
    }

    private var dispatcher: NavigationEventDispatcher? = null

    private fun ComposeUiTest.show(
        calls: Calls,
        state: CowbellUiState = CowbellUiState(),
        width: Int = ColumnWidth,
        height: Int = 205,
        fontScale: Float = 1f,
    ) {
        setContent {
            dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalHapticFeedback provides calls.hapticFeedback,
            ) {
                OrpheusTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        CowbellControlColumn(state, calls.actions, hasNativeEngine = true)
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.chip(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag)

    private fun ComposeUiTest.sliderOpen(): Boolean =
        onAllNodes(hasTestTag(CowbellCtlSliderTag)).fetchSemanticsNodes().isNotEmpty()

    /** The slider's rail in window coordinates. */
    private fun ComposeUiTest.rail() = onNodeWithTag(CowbellCtlSliderTag).fetchSemanticsNode().boundsInWindow

    /** The chip's top-left in window coordinates, to turn a window point into the chip's own. */
    private fun ComposeUiTest.chipOrigin(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.topLeft

    @Test
    fun modeDropdownListsTapShakeHoldAndSetsMode() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        for (item in listOf("Tap", "Shake", "Hold")) onNodeWithText(item).assertExists()
        onNodeWithText("Hold").performClick()
        waitForIdle()
        assertEquals(listOf(CowbellMode.SYNTH), calls.modes)
        onNodeWithText("Shake").assertDoesNotExist()
    }

    @Test
    fun openingTheModeMenuCountsTowardTheHint() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(modeHint = true))
        chip(CowbellCtlModeTag).performClick()
        assertEquals(1, calls.modeMenuOpens)
    }

    @Test
    fun modeMenuHasNoMotionWithoutSensors() = runComposeUiTest {
        show(Calls())
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        onNodeWithTag(CowbellCtlMotionTag).assertDoesNotExist()
    }

    @Test
    fun modeMenuTurnsMotionOn() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(motionAvailable = true))
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        onNodeWithText("Motion off").performClick()
        waitForIdle()
        assertEquals(listOf(true), calls.motions)
        onNodeWithTag(CowbellCtlMotionTag).assertDoesNotExist()
    }

    @Test
    fun shakeCanTurnMotionOff() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(mode = CowbellMode.SHAKE, motion = true, motionAvailable = true))
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        onNodeWithText("Motion on").performClick()
        assertEquals(listOf(false), calls.motions)
    }

    @Test
    fun engineTapOpensThePicker() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        val picker = SemanticsMatcher("the engine picker") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("$CowbellCtlEngineTag-") == true
        }
        assertEquals(0, onAllNodes(picker).fetchSemanticsNodes().size, "the picker is open already")
        chip(CowbellCtlEngineTag).performClick()
        waitForIdle()
        assertTrue(onAllNodes(picker).fetchSemanticsNodes().isNotEmpty(), "the picker did not open")
    }

    @Test
    fun octaveListSetsOctave() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlOctaveTag).performClick()
        waitForIdle()
        for (item in listOf("-2", "-1", "+1", "+2")) onNodeWithText(item).assertExists()
        onNodeWithText("+1").performClick()
        waitForIdle()
        assertEquals(listOf(1), calls.octaves)
    }

    @Test
    fun bendTapFlipsScale() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(bendScale = false))
        onNodeWithText("Bend Free").assertExists()
        chip(CowbellCtlBendTag).performClick()
        assertEquals(listOf(true), calls.bendScales)
    }

    @Test
    fun bendScaleReadsScale() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(bendScale = true))
        onNodeWithText("Bend Scale").assertExists()
        chip(CowbellCtlBendTag).performClick()
        assertEquals(listOf(false), calls.bendScales)
    }

    @Test
    fun lockTapTogglesGrid() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(grid = true, gridStrength = 0.7f))
        chip(CowbellCtlLockTag).performClick()
        assertEquals(listOf(false), calls.grids)
        assertTrue(calls.locks.isEmpty(), "a tap wrote LOCK: ${calls.locks}")
        assertFalse(sliderOpen(), "a tap opened the slider")
    }

    @Test
    fun lockChipReadsOffOrItsValue() = runComposeUiTest {
        val off = Calls()
        show(off, CowbellUiState(grid = false, gridStrength = 0.7f))
        onNodeWithText("Lock off").assertExists()
    }

    @Test
    fun lockChipShowsItsPercent() = runComposeUiTest {
        // Strength 0.55 is a LOCK of (0.55 - 0.15) / 0.85, about 47.
        val calls = Calls()
        show(calls, CowbellUiState(grid = true, gridStrength = 0.55f))
        onNodeWithText("Lock 47").assertExists()
    }

    @Test
    fun lockLongPressSlideSetsLock() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(grid = false))
        val origin = chipOrigin(CowbellCtlLockTag)
        chip(CowbellCtlLockTag).performTouchInput {
            down(center)
            advanceEventTime(700)
            // The next event is what lets the timeout land.
            moveBy(Offset(0f, -1f))
        }
        waitForIdle()
        assertTrue(sliderOpen(), "the slider did not open at the timeout")
        assertEquals(listOf(HapticFeedbackType.LongPress), calls.haptics)
        assertTrue(calls.locks.isEmpty(), "a still hold wrote ${calls.locks}")
        val rail = rail()
        chip(CowbellCtlLockTag).performTouchInput {
            // From the chip to the rail's top, in steps.
            val from = center
            val to = Offset(rail.center.x - origin.x, rail.top - origin.y)
            repeat(10) { moveTo(from + (to - from) * ((it + 1) / 10f)) }
        }
        waitForIdle()
        assertTrue(calls.locks.size >= 2, "locks: ${calls.locks}")
        assertTrue(calls.locks.zipWithNext().all { (a, b) -> b >= a }, "not rising: ${calls.locks}")
        assertTrue(calls.locks.last() >= 0.95f, "the last LOCK is ${calls.locks.last()}")
        assertTrue(sliderOpen(), "the slider closed under the finger")
        chip(CowbellCtlLockTag).performTouchInput { up() }
        waitForIdle()
        assertFalse(sliderOpen(), "lifting left the slider open")
        assertTrue(calls.grids.isEmpty(), "the slide also tapped the chip: ${calls.grids}")
    }

    @Test
    fun lockLongPressSurvivesDriftBeforeTimeout() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlLockTag).performTouchInput {
            down(center)
            // 12dp up, still on the 28dp chip: a finger rolling as it holds.
            moveBy(Offset(0f, -12f))
            advanceEventTime(700)
            moveBy(Offset(0f, 1f))
        }
        waitForIdle()
        assertTrue(sliderOpen(), "a drifting hold did not open the slider")
        chip(CowbellCtlLockTag).performTouchInput { up() }
        waitForIdle()
        assertFalse(sliderOpen())
        assertTrue(calls.grids.isEmpty() && calls.locks.isEmpty(), "grids ${calls.grids} locks ${calls.locks}")
    }

    @Test
    fun aStillHoldThenLiftChangesNothing() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlLockTag).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, -1f))
        }
        waitForIdle()
        assertTrue(sliderOpen())
        chip(CowbellCtlLockTag).performTouchInput { up() }
        waitForIdle()
        assertFalse(sliderOpen())
        assertTrue(calls.grids.isEmpty() && calls.locks.isEmpty(), "the lift tapped: grids ${calls.grids} locks ${calls.locks}")
    }

    @Test
    fun aQuickTapDoesNotOpenTheSlider() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlLockTag).performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        waitForIdle()
        assertFalse(sliderOpen())
        // LOCK starts on, so the tap turns it off.
        assertEquals(listOf(false), calls.grids)
        assertTrue(calls.haptics.isEmpty())
    }

    @Test
    fun levelTapOpensSliderAndOutsideTapCloses() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlLevelTag).performClick()
        waitForIdle()
        assertTrue(sliderOpen(), "a tap did not open the slider")
        assertTrue(calls.levels.isEmpty(), "opening wrote ${calls.levels}")
        // A tap on the rail itself sets the value and leaves it open. Half way up is a LEVEL of 2/3.
        onNodeWithTag(CowbellCtlSliderTag).performTouchInput { click(Offset(width / 2f, height * 0.5f)) }
        waitForIdle()
        assertTrue(calls.levels.isNotEmpty() && calls.levels.last() in 0.62f..0.72f, "levels: ${calls.levels}")
        assertEquals(0f, calls.mores.last(), "MORE below the notch")
        assertTrue(sliderOpen(), "a tap on the rail closed it")
        // Outside, far from the column and the rail.
        onAllNodes(isRoot()).onFirst().performTouchInput { click(Offset(38f, 200f)) }
        waitForIdle()
        assertFalse(sliderOpen(), "an outside tap left the slider open")
        assertEquals(0, calls.releases, "closing released something")
    }

    @Test
    fun volChipTurnsToMoreAboveMax() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(level = 0.7f, more = 0f))
        onNodeWithText("Vol 70").assertExists()
        show(calls, CowbellUiState(level = 1f, more = 0f))
        onNodeWithText("Vol 100").assertExists()
        show(calls, CowbellUiState(level = 1f, more = 0.5f))
        onNodeWithText("More 150").assertExists()
        onNodeWithText("Vol 100").assertDoesNotExist()
        show(calls, CowbellUiState(level = 1f, more = 1f))
        onNodeWithText("More 200").assertExists()
        assertEquals("Vol 100", levelChipText(1f, 0f))
        assertEquals("More 101", levelChipText(1f, 0.01f))
        assertEquals("More 200", levelChipText(1f, 1f))
    }

    /** A tap on the open Level rail at [position], 0 at the bottom. */
    private fun ComposeUiTest.tapRailAt(position: Float) {
        onNodeWithTag(CowbellCtlSliderTag).performTouchInput { click(Offset(width / 2f, height * (1f - position))) }
        waitForIdle()
    }

    @Test
    fun sliderNotchSnapsToMax() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(level = 0.4f))
        chip(CowbellCtlLevelTag).performClick()
        waitForIdle()
        onNodeWithText("100").assertExists()
        onNodeWithText("MORE").assertExists()
        // Within the dead zone either side of the notch lands on max exactly, with no MORE.
        for (position in listOf(0.73f, 0.75f, 0.77f)) {
            tapRailAt(position)
            assertEquals(1f, calls.levels.last(), "level at $position")
            assertEquals(0f, calls.mores.last(), "more at $position")
        }
        // Past the detent MORE rises and the level holds at 1.
        // Half way from the dead zone's top edge to the top of travel is half MORE.
        tapRailAt(((MoreNotch + MoreDetent) + 1f) / 2f)
        assertEquals(1f, calls.levels.last())
        assertTrue(calls.mores.last() in 0.45f..0.55f, "more: ${calls.mores.last()}")
        tapRailAt(1f)
        assertEquals(1f, calls.mores.last())
        // Back below the notch MORE returns to 0.
        tapRailAt(0.375f)
        assertTrue(calls.levels.last() in 0.45f..0.55f, "level: ${calls.levels.last()}")
        assertEquals(0f, calls.mores.last())
    }

    @Test
    fun moreMappingRoundTrips() {
        for (level in listOf(0f, 0.1f, 0.5f, 0.7f, 0.9f)) {
            val (l, m) = moreSliderValues(moreSliderPosition(level, 0f))
            assertEquals(level, l, 1e-5f)
            assertEquals(0f, m)
        }
        for (more in listOf(0.2f, 0.5f, 0.8f, 1f)) {
            val (l, m) = moreSliderValues(moreSliderPosition(1f, more))
            assertEquals(1f, l)
            assertEquals(more, m, 1e-5f)
        }
        assertEquals(MoreNotch, moreSliderPosition(1f, 0f))
        assertEquals(1f to 0f, moreSliderValues(MoreNotch))
        assertEquals(0f, moreSliderPosition(0f, 0f))
        assertEquals(1f, moreSliderPosition(1f, 1f))
        assertEquals(0f to 0f, moreSliderValues(0f))
        assertEquals(1f to 1f, moreSliderValues(1f))
    }

    @Test
    fun moreMappingDetentAndZones() {
        // The dead zone: 3% of travel either side lands exactly on max.
        for (p in listOf(MoreNotch - MoreDetent, MoreNotch - 0.01f, MoreNotch + 0.01f, MoreNotch + MoreDetent)) {
            assertEquals(1f to 0f, moreSliderValues(p), "position $p")
        }
        // Just outside it the values move on.
        val (below, belowMore) = moreSliderValues(MoreNotch - MoreDetent - 0.01f)
        assertTrue(below < 1f && belowMore == 0f)
        val (above, aboveMore) = moreSliderValues(MoreNotch + MoreDetent + 0.01f)
        assertTrue(above == 1f && aboveMore > 0f)
        // The notch value maps to the dead zone's centre.
        assertEquals(MoreNotch, moreSliderPosition(1f, 0f))
        // Level only below the notch, MORE only above, and the level holds at 1 up there.
        var p = 0f
        while (p <= 1f) {
            val (l, m) = moreSliderValues(p)
            if (p < MoreNotch) assertEquals(0f, m, "MORE below the notch at $p")
            if (p > MoreNotch) assertEquals(1f, l, "level above the notch at $p")
            assertTrue(l in 0f..1f && m in 0f..1f)
            p += 0.01f
        }
        // Monotonic: sliding up never lowers either value.
        val steps = (0..100).map { moreSliderValues(it / 100f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second })
        assertEquals(0f to 0f, moreSliderValues(Float.NaN))
    }

    @Test
    fun leavingTheNotchUpwardStartsMoreAtZero() {
        val (level, more) = moreSliderValues(MoreNotch + MoreDetent + 0.003f)
        assertEquals(1f, level)
        assertTrue(more > 0f && more < 0.02f, "more just above the dead zone: $more")
        assertEquals(1f to 0f, moreSliderValues(MoreNotch + MoreDetent))
    }

    @Test
    fun leavingTheNotchDownwardStartsLevelAtOne() {
        val (level, more) = moreSliderValues(MoreNotch - MoreDetent - 0.003f)
        assertEquals(0f, more)
        assertTrue(level < 1f && level > 0.98f, "level just below the dead zone: $level")
        assertEquals(1f to 0f, moreSliderValues(MoreNotch - MoreDetent))
        // Level 0 is the bottom of travel, and the edge of the zone is level 1.
        assertEquals(0f, moreSliderPosition(0f, 0f))
        assertEquals(1f, moreSliderValues(MoreNotch - MoreDetent).first)
    }

    @Test
    fun levelLongPressSlideSetsLevel() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(level = 0.2f))
        val origin = chipOrigin(CowbellCtlLevelTag)
        chip(CowbellCtlLevelTag).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, -1f))
        }
        waitForIdle()
        assertTrue(sliderOpen())
        val rail = rail()
        chip(CowbellCtlLevelTag).performTouchInput {
            val from = center
            val to = Offset(rail.center.x - origin.x, rail.top - origin.y)
            repeat(10) { moveTo(from + (to - from) * ((it + 1) / 10f)) }
        }
        waitForIdle()
        assertTrue(calls.levels.last() >= 0.95f, "levels: ${calls.levels}")
        assertTrue(calls.mores.last() >= 0.95f, "mores: ${calls.mores}")
        chip(CowbellCtlLevelTag).performTouchInput { up() }
        waitForIdle()
        assertFalse(sliderOpen())
    }

    @Test
    fun popupsLeaveHeldNotesAlone() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        onNodeWithText("Shake").performClick()
        chip(CowbellCtlLevelTag).performClick()
        waitForIdle()
        chip(CowbellCtlEngineTag).performClick()
        waitForIdle()
        assertEquals(0, calls.releases, "a popup released a held note")
    }

    @Test
    fun columnFitsAtFontScale13() = runComposeUiTest {
        // The longest text each chip can show.
        val states = listOf(
            CowbellUiState(mode = CowbellMode.SHAKE, engine = -1, octave = -2, level = 1f, grid = true, gridStrength = 1f, bendScale = true),
            CowbellUiState(mode = CowbellMode.SYNTH, engine = 2, octave = 2, level = 0.7f, grid = false, bendScale = false),
            // The Vol chip becomes the longest one: "More 200".
            CowbellUiState(mode = CowbellMode.SHAKE, engine = -1, level = 1f, more = 1f, grid = true, gridStrength = 1f),
        )
        for (state in states) {
            val calls = Calls()
            show(calls, state, width = ColumnWidth, height = 205, fontScale = 1.3f)
            val what = "${ColumnWidth}x205 @1.3 $state"
            val tags = listOf(CowbellCtlModeTag, CowbellCtlEngineTag, CowbellCtlOctaveTag, CowbellCtlLevelTag, CowbellCtlLockTag, CowbellCtlBendTag)
            val bounds = tags.map { tag -> tag to onNodeWithTag(tag).fetchSemanticsNode().laidOut }
            for ((tag, b) in bounds) {
                assertTrue(b.left >= -0.5f && b.top >= -0.5f && b.right <= ColumnWidth + 0.5f && b.bottom <= 205.5f, "$what: $tag $b leaves the column")
                assertTrue(b.height >= ControlChipHeight.value - 0.5f, "$what: $tag is only ${b.height} tall")
            }
            for ((a, b) in bounds.zipWithNext()) {
                assertTrue(a.second.bottom <= b.second.top + 0.5f, "$what: ${a.first} overlaps ${b.first}")
            }
            val texts = onRoot(useUnmergedTree = true).fetchSemanticsNode().walk().filter { it.text().isNotBlank() }
            assertTrue(texts.size >= 6, "$what: only ${texts.map { it.text() }}")
            for (node in texts) {
                assertFalse(node.textOverflows(), "$what: '${node.text()}' wants ${node.textWidthShortfall()}px more than it gets (${node.size.width}px)")
                val b = node.laidOut
                assertTrue(b.right <= ColumnWidth + 0.5f, "$what: '${node.text()}' runs to ${b.right}")
            }
        }
    }

    @Test
    fun dpadHoldOkOnLockOpensSliderArrowsMoveItBackCloses() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(grid = true, gridStrength = 0.55f))
        val input = DirectNavigationEventInput().also { assertNotNull(dispatcher).addInput(it) }
        chip(CowbellCtlLockTag).performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        chip(CowbellCtlLockTag).performKeyInput {
            keyDown(Key.DirectionCenter)
            // A held key repeats its key-down.
            advanceEventTime(1_000)
        }
        waitForIdle()
        assertTrue(sliderOpen(), "holding OK did not open the slider")
        assertTrue(calls.grids.isEmpty(), "the hold also tapped: ${calls.grids}")
        // The lift of the opening press leaves it open and taps nothing.
        chip(CowbellCtlLockTag).performKeyInput { keyUp(Key.DirectionCenter) }
        waitForIdle()
        assertTrue(sliderOpen(), "lifting OK closed the slider")
        assertTrue(calls.grids.isEmpty(), "the lift tapped: ${calls.grids}")

        onNodeWithTag(CowbellCtlSliderTag).performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(2, calls.locks.size, "locks: ${calls.locks}")
        assertTrue(calls.locks[1] > calls.locks[0], "not rising: ${calls.locks}")
        onNodeWithTag(CowbellCtlSliderTag).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(calls.locks.last() < calls.locks[1], "Down did not lower it: ${calls.locks}")

        input.backCompleted()
        waitForIdle()
        assertFalse(sliderOpen(), "Back left the slider open")
    }

    @Test
    fun dpadOkTapOnLockTogglesGrid() = runComposeUiTest {
        val calls = Calls()
        show(calls, CowbellUiState(grid = false))
        chip(CowbellCtlLockTag).performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        chip(CowbellCtlLockTag).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        assertEquals(listOf(true), calls.grids)
        assertFalse(sliderOpen())
    }

    @Test
    fun backClosesAnOpenDropdown() = runComposeUiTest {
        val calls = Calls()
        show(calls)
        val input = DirectNavigationEventInput().also { assertNotNull(dispatcher).addInput(it) }
        chip(CowbellCtlModeTag).performClick()
        waitForIdle()
        onNodeWithText("Hold").assertExists()
        input.backCompleted()
        waitForIdle()
        onNodeWithText("Hold").assertDoesNotExist()
        assertTrue(calls.modes.isEmpty())
    }

    @Test
    fun fillDropdownListsTheFillsAndSetsOne() = runComposeUiTest {
        val fills = mutableListOf<CowbellFill>()
        setContent {
            OrpheusTheme {
                Box(Modifier.size(120.dp, 100.dp)) { FillDropdown(CowbellFill.MARBLE, { fills += it }) }
            }
        }
        waitForIdle()
        onNodeWithTag(CowbellFillDropdownTag).performClick()
        waitForIdle()
        for (item in listOf("Beads", "Seeds", "Jingles")) onNodeWithText(item).assertExists()
        onNodeWithText("Jingles").performClick()
        waitForIdle()
        assertEquals(listOf(CowbellFill.JINGLES), fills)
    }

    private companion object {
        /** The width the column is given at 1.3 font scale. */
        const val ColumnWidth = 80
    }
}

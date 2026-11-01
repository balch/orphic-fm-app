package org.balch.orpheus.features.cowbell.dj

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.cowbell.CowbellFeature
import org.balch.orpheus.features.cowbell.CowbellPanelActions
import org.balch.orpheus.features.cowbell.CowbellScene
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.features.cowbell.RecordingActions
import org.balch.orpheus.features.cowbell.laidOut
import org.balch.orpheus.features.cowbell.text
import org.balch.orpheus.features.cowbell.textOverflows
import org.balch.orpheus.features.cowbell.textWidthShortfall
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The DJ panel: four full-height columns (controls, knobs, bell, fader).
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellDjPanelTest*'
 */
@OptIn(ExperimentalTestApi::class)
class CowbellDjPanelTest {

    private val BellTag = "cowbell-bell"

    private fun CowbellScene.has(text: String) = nodes().any { it.text() == text }

    private fun CowbellScene.hasTag(tag: String) = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /** A drag of [dy] px (negative is up) from [from], in small steps so it clears touch slop. */
    private fun CowbellScene.drag(from: Offset, dy: Float, steps: Int = 8) {
        press(from)
        repeat(steps) { move(Offset(from.x, from.y + dy * (it + 1) / steps)) }
        release(Offset(from.x, from.y + dy))
    }

    private fun scene(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        state: CowbellUiState = CowbellUiState(),
        recording: RecordingActions = RecordingActions(),
    ) = CowbellScene(width, height, fontScale) {
        CowbellDjPanel(recording.feature(state), Modifier.fillMaxSize())
    }

    @Test
    fun fourColumnsLeftToRight() {
        scene(412, 290).use { s ->
            val lefts = listOf(CowbellCtlModeTag, CowbellDjKnobsTag, CowbellDjBellTag, CowbellDjFaderTag)
                .map { it to s.tagged(it).laidOut.left }
            assertEquals(lefts.sortedBy { it.second }.map { it.first }, lefts.map { it.first }, "columns out of order: $lefts")
            assertTrue(lefts.zipWithNext().all { (a, b) -> a.second < b.second }, "columns overlap on the left edge: $lefts")
            // Each column ends before the next begins.
            val controls = s.tagged(CowbellCtlModeTag).laidOut
            assertTrue(controls.right <= s.tagged(CowbellDjKnobsTag).laidOut.left + 0.5f, "controls run into the knobs")
            assertTrue(s.tagged(CowbellDjKnobsTag).laidOut.right <= s.tagged(CowbellDjBellTag).laidOut.left + 0.5f, "knobs run into the bell")
            assertTrue(s.tagged(CowbellDjBellTag).laidOut.right <= s.tagged(CowbellDjFaderTag).laidOut.left + 0.5f, "bell runs into the fader")
        }
    }

    @Test
    fun theHandleTucksTheControlsAwayAndBringsThemBack() {
        scene(412, 290).use { s ->
            s.frames(32)
            val panel = s.tagged(CowbellDjPanelTag).laidOut
            val wideBell = s.tagged(CowbellDjBellTag).laidOut
            val tap = { val at = s.tagged(CowbellDjCollapseTag).laidOut.center; s.press(at); s.release(at); s.frames(600) }
            tap()
            assertTrue(!s.hasTag(CowbellCtlModeTag) && !s.hasTag(CowbellDjKnobsTag), "the controls stayed after collapsing")
            val bell = s.tagged(CowbellDjBellTag).laidOut
            val fader = s.tagged(CowbellDjFaderTag).laidOut
            val handle = s.tagged(CowbellDjCollapseTag).laidOut
            // The pill stays in the top-left corner; the bell and fader sit as a pair in the middle.
            assertEquals(panel.left + 6f, handle.left, 1f, "the pill left its corner: $handle")
            assertEquals(panel.top + 6f, handle.top, 1f, "the pill left its corner: $handle")
            val groupMid = (bell.left + fader.right) / 2f
            assertEquals(panel.center.x, groupMid, 8f, "the collapsed group is off centre: $handle $bell $fader")
            assertTrue(bell.width < wideBell.width + 80f, "the collapsed bell column ran wide: ${bell.width}")
            tap()
            assertTrue(s.hasTag(CowbellCtlModeTag) && s.hasTag(CowbellDjKnobsTag), "the controls did not come back")
            assertEquals(wideBell.left, s.tagged(CowbellDjBellTag).laidOut.left, 1f, "expanding again moved the bell")
        }
    }

    @Test
    fun bellRunsFullHeightAndTheFaderStopsAtItsCap() {
        for (mode in CowbellMode.entries) {
            scene(412, 290, state = CowbellUiState(mode = mode)).use { s ->
                s.frames(64)
                val panel = s.tagged(CowbellDjPanelTag).laidOut
                val fader = s.tagged(CowbellDjFaderTag).laidOut
                val bell = s.tagged(CowbellDjBellTag).laidOut
                // The scene's density is 1, so px are dp.
                assertEquals(DjFaderMaxTrack.value, fader.height, 1f, "$mode: the fader track")
                assertEquals(panel.center.y, fader.center.y, 2f, "$mode: the fader is centred")
                assertTrue(bell.height >= panel.height * 0.9f, "$mode: the bell column is ${bell.height} of ${panel.height}")
                // Every column centres on one line, Shake's Fill dropdown included.
                assertEquals(fader.center.y, s.bell().center.y, 3f, "$mode: the bell is centred with the fader")
                assertEquals(fader.center.y, s.tagged(CowbellDjKnobsTag).laidOut.center.y, 3f, "$mode: the knobs")
            }
        }
    }

    @Test
    fun fillDropdownOnlyInShake() {
        for (mode in CowbellMode.entries) {
            scene(412, 290, state = CowbellUiState(mode = mode)).use { s ->
                s.frames(32)
                assertEquals(mode == CowbellMode.SHAKE, s.hasTag(CowbellFillDropdownTag), "$mode: fill dropdown")
                if (mode == CowbellMode.SHAKE) {
                    val fill = s.tagged(CowbellFillDropdownTag).laidOut
                    val column = s.tagged(CowbellDjBellTag).laidOut
                    // Under the drawn bell: low in the column and centred across it.
                    assertTrue(fill.top > column.center.y && fill.bottom <= column.bottom + 0.5f, "the fill dropdown $fill is not under the bell in $column")
                    assertEquals(column.center.x, fill.center.x, 2f, "the fill dropdown is centred under the bell")
                    assertTrue(fill.left >= column.left - 0.5f && fill.right <= column.right + 0.5f, "the fill dropdown $fill leaves the bell column $column")
                }
            }
        }
    }

    @Test
    fun noSettingsBarOrViewOrKnobRow() {
        scene(412, 290).use { s ->
            s.frames(32)
            val tags = s.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            assertTrue(tags.none { it.startsWith("cowbell-settings-") }, "settings left over: $tags")
            val column = s.tagged(CowbellDjKnobsTag).laidOut
            val knobs = tags.filter { it.startsWith("cowbell-dj-knob-") }
            assertEquals(setOf(CowbellDjMetalTag, CowbellDjRingTag, CowbellDjIntensityTag), knobs.toSet(), "knobs: $knobs")
            for (tag in knobs) {
                val b = s.tagged(tag).laidOut
                assertTrue(b.left >= column.left - 0.5f && b.right <= column.right + 0.5f && b.top >= column.top - 0.5f && b.bottom <= column.bottom + 0.5f, "$tag $b is outside the knobs column $column")
            }
            // LOCK lives on a chip now, and no knob row sits under the bell.
            assertFalse(s.has("LOCK"), "a LOCK knob is still on the panel")
            val panel = s.tagged(CowbellDjPanelTag).laidOut
            assertTrue(s.tagged(CowbellDjBellTag).laidOut.bottom >= panel.bottom - 4f, "something sits under the bell")
        }
    }

    // Hold needs a pointer that stays down while the popups open, which the scene kit's single
    // pointer cannot do beside the dropdown's own events, so this one runs on the compose test clock.
    @Test
    fun openingAPopupKeepsAHeldNote() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val actions = CowbellPanelActions.EMPTY.copy(
            onHold = { _, _ -> calls += "hold" },
            onRelease = { calls += "release" },
            releaseAll = { calls += "releaseAll" },
        )
        val feature = object : CowbellFeature {
            override val stateFlow: StateFlow<CowbellUiState> = MutableStateFlow(CowbellUiState(mode = CowbellMode.SYNTH))
            override val actions = actions
        }
        // The bell animates for good, so the clock is stepped by hand rather than waited idle.
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                OrpheusTheme {
                    Box(Modifier.size(412.dp, 290.dp)) { CowbellDjPanel(feature, Modifier.fillMaxSize()) }
                }
            }
        }
        fun settle() = mainClock.advanceTimeBy(100)
        settle()

        onNodeWithTag(BellTag).performTouchInput { down(center) }
        settle()
        assertTrue("hold" in calls, "the bell did not hold: $calls")

        // The mode dropdown, by its click action: the touch pointer is busy holding the bell.
        onNodeWithTag(CowbellCtlModeTag).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        onNodeWithText("Shake").assertExists()
        assertTrue(calls.none { it == "release" || it == "releaseAll" }, "the mode dropdown let go: $calls")

        // Picking the current mode closes the dropdown; the note must outlive that too.
        onNodeWithText("Hold").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertFalse(onAllNodesWithTag(CowbellFillDropdownTag), "sanity: not Shake")
        assertTrue(calls.none { it == "release" || it == "releaseAll" }, "closing the dropdown let go: $calls")

        // The Lock slider, by a held OK, as a D-pad does.
        onNodeWithTag(CowbellCtlLockTag).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        onNodeWithTag(CowbellCtlLockTag).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
        }
        mainClock.advanceTimeBy(1_000)
        assertTrue(onAllNodesWithTag(CowbellCtlSliderTag), "the Lock slider did not open")
        assertTrue(calls.none { it == "release" || it == "releaseAll" }, "the Lock slider let go: $calls")
        assertEquals(1, calls.count { it == "hold" }, "a popup struck the bell again: $calls")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTag(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun faderBendsInEveryMode() {
        for (mode in CowbellMode.entries) {
            val recording = RecordingActions()
            scene(412, 290, state = CowbellUiState(mode = mode), recording = recording).use { s ->
                val fader = s.tagged(CowbellDjFaderTag).laidOut
                s.drag(fader.center, dy = -fader.height / 4f)
                assertTrue(recording.bends.any { it > 0f }, "$mode: no upward bend in ${recording.bends}")
                s.frames(2_500)
                assertTrue("bendDone" in recording.calls, "$mode: no bendDone in ${recording.calls}")
                assertFalse("fillStrike" in recording.calls, "$mode: the fader launched the fill")
                assertFalse("strike" in recording.calls || "hold" in recording.calls, "$mode: the fader struck the bell: ${recording.calls}")
            }
        }
    }

    /** Tab and focus search walk the tree in this order: controls, knobs, bell, fader. */
    @Test
    fun composesInReadingOrder() {
        scene(412, 290).use { s ->
            val wanted = listOf(CowbellCtlModeTag, "BODY", "RING", "INTENSITY", BellTag, CowbellDjFaderTag)
            val order = s.nodes().mapNotNull { n ->
                n.text().takeIf { it in wanted } ?: n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it in wanted }
            }
            assertEquals(wanted, order)
        }
    }

    @Test
    fun knobsAreTight() {
        for (fontScale in listOf(1f, 1.3f)) {
            scene(412, 290, fontScale).use { s ->
                s.frames(32)
                val knobs = listOf(CowbellDjMetalTag, CowbellDjRingTag, CowbellDjIntensityTag).map { s.tagged(it).laidOut }
                val gaps = knobs.zipWithNext().map { (a, b) -> b.top - a.bottom }
                assertTrue(gaps.all { it in -0.5f..6.5f }, "@$fontScale: the knob gaps are $gaps, over 6dp")
            }
        }
    }

    /**
     * Nothing clips, truncates or leaves the panel, and the bell keeps at least 64dp of width.
     * Scenes lay out at 1 px per dp.
     */
    private fun assertFits(width: Int, height: Int, fontScale: Float, mode: CowbellMode = CowbellMode.TAP) {
        val what = "${width}x$height @$fontScale $mode"
        scene(width, height, fontScale, CowbellUiState(mode = mode)).use { s ->
            s.frames(64)
            val panel = s.tagged(CowbellDjPanelTag).laidOut
            fun inside(label: String, node: SemanticsNode) {
                val b = node.laidOut
                assertTrue(
                    b.left >= panel.left - 0.5f && b.top >= panel.top - 0.5f && b.right <= panel.right + 0.5f && b.bottom <= panel.bottom + 0.5f,
                    "$what: '$label' $b runs outside the panel $panel",
                )
                assertTrue(b.width > 4f && b.height > 4f, "$what: '$label' is squeezed to $b")
            }
            val labelled = s.nodes().filter { it.text().isNotBlank() }
            for (label in listOf("BODY", "RING", "INTENSITY")) {
                assertTrue(labelled.any { it.text() == label }, "$what: no '$label'")
            }
            for (node in labelled) {
                inside(node.text(), node)
                assertTrue(
                    node.boundsInRoot.height >= node.laidOut.height - 0.5f && node.boundsInRoot.width >= node.laidOut.width - 0.5f,
                    "$what: '${node.text()}' is clipped to ${node.boundsInRoot} of ${node.laidOut}",
                )
                assertFalse(node.textOverflows(), "$what: '${node.text()}' wants ${node.textWidthShortfall()}px more than it gets")
            }
            for (tag in listOf(CowbellDjFaderTag, CowbellDjKnobsTag, CowbellDjBellTag, BellTag, CowbellCtlModeTag, CowbellCtlBendTag)) {
                inside(tag, s.tagged(tag))
            }
            for (tag in listOf(CowbellDjMetalTag, CowbellDjRingTag, CowbellDjIntensityTag)) inside(tag, s.tagged(tag))
            val bell = s.bell()
            assertTrue(bell.width >= 64f, "$what: the bell is only ${bell.width}dp wide")
            assertTrue(bell.height >= 64f, "$what: the bell is only ${bell.height}dp tall")
            val fader = s.tagged(CowbellDjFaderTag).laidOut
            assertTrue(fader.height >= 40f, "$what: the fader is only ${fader.height}dp tall")
        }
    }

    /** The closed Duo's 205dp slot at the user's font scale: the worst case. */
    @Test
    fun fitsDuoClosedAtFontScale13() {
        assertFits(412, 205, 1.3f)
        assertFits(412, 205, 1.3f, CowbellMode.SHAKE)
    }

    /** The Fold cover, or one half of a Portrait pair. */
    @Test
    fun fitsPairHalfAtFontScale13() {
        assertFits(350, 290, 1.3f)
        assertFits(350, 290, 1.3f, CowbellMode.SHAKE)
    }

    @Test
    fun fitsATallSlotAtFontScaleOne() {
        assertFits(412, 520, 1f)
        assertFits(320, 330, 1f)
    }

    @Test
    fun bodyNamesTheResonatorItPlays() {
        // The segment edges of cowbell_macros.h: five 0.2-wide steps.
        assertEquals("String", metalModeName(0f))
        assertEquals("String", metalModeName(0.19f))
        assertEquals("Sitar", metalModeName(0.2f))
        assertEquals("Wire", metalModeName(0.5f))
        assertEquals("Modal", metalModeName(0.7f))
        assertEquals("FM", metalModeName(0.8f))
        assertEquals("FM", metalModeName(1f))
        scene(412, 290, state = CowbellUiState(metal = 0.7f)).use { s ->
            s.frames(32)
            assertTrue(s.has("Modal"), "the BODY knob does not name its mode")
        }
    }
}

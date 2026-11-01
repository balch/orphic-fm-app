package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchCable
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PatchBayUiTest {

    /** The bay over a patch the test can watch; each change is applied, so the bay redraws from it. */
    private class Harness(start: DuoPatch, engine: OrpheusEngineId) {
        var patch by mutableStateOf(start)
        var engine by mutableStateOf(engine)
        val changes = mutableListOf<DuoPatch>()
        fun apply(next: DuoPatch) {
            changes += next
            patch = next
        }
    }

    private fun ComposeUiTest.bay(
        start: DuoPatch = DuoPatch(),
        engine: OrpheusEngineId = OrpheusEngineId.VA,
        height: Int = 520,
    ): Harness {
        val h = Harness(start, engine)
        setContent {
            OrpheusTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    PatchBay(h.patch, h.engine, h::apply, Modifier.size(560.dp, height.dp))
                }
            }
        }
        return h
    }

    private fun ComposeUiTest.centerOf(tag: String): Offset =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center

    /** A touch drag from [fromTag]'s centre to [to] (window coordinates), in small steps. */
    private fun ComposeUiTest.dragTo(fromTag: String, to: Offset) {
        val origin = onNodeWithTag(fromTag).fetchSemanticsNode().boundsInWindow.topLeft
        onNodeWithTag(fromTag).performTouchInput {
            down(center)
            val start = center
            val end = to - origin
            for (step in 1..12) moveTo(start + (end - start) * (step / 12f))
            up()
        }
    }

    private fun emphasis(value: CableEmphasis) = SemanticsMatcher.expectValue(CableEmphasisKey, value)

    @Test
    fun dragOutputToInputConnects() = runComposeUiTest {
        val h = bay()
        waitForIdle()
        dragTo("patch-out-LFO_A", centerOf("patch-in-MORPH-BOTH"))
        waitForIdle()
        assertEquals(
            listOf(PatchCable(PatchSource.LFO_A, PatchDest.MORPH, VoiceSlot.BOTH, 0.5f)),
            h.changes.last().cables,
        )
        onNodeWithTag("patch-cable-LFO_A-MORPH-BOTH").assertExists()
    }

    @Test
    fun dragPlugOffDisconnects() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f))
        waitForIdle()
        val plug = centerOf("patch-in-MORPH-BOTH")
        // Halfway back across the cable area, where nothing is.
        val empty = (plug + centerOf("patch-out-LFO_A")) / 2f + Offset(0f, 60f)
        dragTo("patch-in-MORPH-BOTH", empty)
        waitForIdle()
        assertEquals(emptyList(), h.changes.last().cables)
        onNodeWithTag("patch-cable-LFO_A-MORPH-BOTH").assertDoesNotExist()
    }

    @Test
    fun familyChipDimsOtherFamilies() = runComposeUiTest {
        bay(
            DuoPatch()
                .connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f)
                .connect(PatchSource.FLUX_X1, PatchDest.TIMBRE, amount = 0.3f),
        )
        onNodeWithTag("patch-cable-LFO_A-MORPH-BOTH").assert(emphasis(CableEmphasis.BRIGHT))
        onNodeWithTag("patch-cable-FLUX_X1-TIMBRE-BOTH").assert(emphasis(CableEmphasis.BRIGHT))

        onNodeWithTag("patch-chip-FLUX").performClick()

        onNodeWithTag("patch-cable-FLUX_X1-TIMBRE-BOTH").assert(emphasis(CableEmphasis.BRIGHT))
        onNodeWithTag("patch-cable-LFO_A-MORPH-BOTH").assert(emphasis(CableEmphasis.DIM))
    }

    @Test
    fun allShowsAmountListFamilyShowsCableKnobs() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = -0.25f))
        onNodeWithTag("patch-amount-row-LFO_A-MORPH-BOTH").assertExists()
        onNodeWithTag("patch-knob-LFO_A-MORPH-BOTH").assertDoesNotExist()

        onNodeWithTag("patch-chip-LFO").performClick()

        onNodeWithTag("patch-amount-row-LFO_A-MORPH-BOTH").assertDoesNotExist()
        onNodeWithTag("patch-knob-LFO_A-MORPH-BOTH").assertExists()
        assertTrue(h.changes.isEmpty(), "focusing changes nothing in the patch")
    }

    @Test
    fun tapParkedCableWakesIt() = runComposeUiTest {
        val parked = DuoPatch().connect(PatchSource.LFO_B, PatchDest.HARMONICS, amount = 0.5f)
            .onEngineChange(OrpheusEngineId.VA, OrpheusEngineId.DX2)
        val h = bay(parked, OrpheusEngineId.VA)
        waitForIdle()
        onNodeWithTag("patch-cable-LFO_B-HARMONICS-BOTH").assert(emphasis(CableEmphasis.PARKED))

        onNodeWithTag("patch-cable-LFO_B-HARMONICS-BOTH").performClick()

        assertEquals(false, h.changes.last().cables.single().parked)
        onNodeWithTag("patch-cable-LFO_B-HARMONICS-BOTH").assert(emphasis(CableEmphasis.BRIGHT))
    }

    @Test
    fun bridgeSwitchClickSplitsPitch() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.3f))
        onNodeWithTag("patch-in-PITCH-BOTH").assertExists()

        onNodeWithTag("patch-bridge-PITCH").performClick()

        val split = h.changes.last()
        assertEquals(false, split.pitchBridged)
        assertEquals(
            setOf(
                PatchCable(PatchSource.LFO_A90, PatchDest.PITCH, VoiceSlot.A, 0.3f),
                PatchCable(PatchSource.LFO_A90, PatchDest.PITCH, VoiceSlot.B, 0.3f),
            ),
            split.cables.toSet(),
        )
        onNodeWithTag("patch-in-PITCH-A").assertExists()
        onNodeWithTag("patch-in-PITCH-B").assertExists()
        // The A jack changed identity (BOTH to A); its cable must still find it and draw.
        onNodeWithTag("patch-cable-LFO_A90-PITCH-A").assertExists()
        onNodeWithTag("patch-cable-LFO_A90-PITCH-B").assertExists()

        // A quarter turn by drag bridges it again; a turn under 45 degrees does nothing.
        fun turn(degrees: Float) = onNodeWithTag("patch-bridge-PITCH").performTouchInput {
            val r = width * 0.4f
            down(center + Offset(r, 0f))
            for (step in 1..9) {
                val a = Math.toRadians((-degrees * step / 9f).toDouble())
                moveTo(center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
            }
            up()
        }
        val before = h.changes.size
        turn(30f)
        assertEquals(before, h.changes.size, "30 degrees is not a turn")
        turn(90f)
        assertEquals(true, h.changes.last().pitchBridged)
    }

    @Test
    fun bridgeSwitchIgnoresReleaseOutsideAndTurnsOnEnter() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.3f))
        onNodeWithTag("patch-bridge-PITCH").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 3f))
            up()
        }
        assertTrue(h.changes.isEmpty(), "a press released off the switch changes nothing")

        onNodeWithTag("patch-bridge-PITCH").requestFocus()
        onNodeWithTag("patch-bridge-PITCH").performKeyInput { pressKey(Key.Enter) }
        assertEquals(false, h.changes.last().pitchBridged)
        onNodeWithTag("patch-bridge-PITCH").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(true, h.changes.last().pitchBridged)
    }

    @Test
    fun dragOnFocusedCableKnobChangesAmount() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.2f))
        onNodeWithTag("patch-chip-LFO").performClick()
        waitForIdle()
        onNodeWithTag("patch-knob-LFO_A-MORPH-BOTH").performTouchInput {
            // The dial is the top 22dp of the knob's box.
            val dial = Offset(centerX, 11f)
            down(dial)
            for (step in 1..10) moveTo(dial - Offset(0f, step * 6f))
            up()
        }
        val cable = h.changes.last().cables.single()
        assertTrue(cable.amount > 0.2f, "dragging up raises the amount, got ${cable.amount}")
        assertEquals(PatchSource.LFO_A to PatchDest.MORPH, cable.source to cable.dest)
    }

    @Test
    fun plugMovesFromOneInputToAnother() = runComposeUiTest {
        val h = bay(DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f))
        waitForIdle()
        dragTo("patch-in-MORPH-BOTH", centerOf("patch-in-TIMBRE-BOTH"))
        waitForIdle()
        assertEquals(
            listOf(PatchCable(PatchSource.LFO_A, PatchDest.TIMBRE, VoiceSlot.BOTH, 0.4f)),
            h.changes.last().cables,
        )
        onNodeWithTag("patch-cable-LFO_A-TIMBRE-BOTH").assertExists()
    }

    @Test
    fun secondaryButtonDoesNotPatch() = runComposeUiTest {
        val h = bay()
        waitForIdle()
        val origin = onNodeWithTag("patch-out-LFO_A").fetchSemanticsNode().boundsInWindow.topLeft
        val target = centerOf("patch-in-MORPH-BOTH") - origin
        onNodeWithTag("patch-out-LFO_A").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            for (step in 1..12) moveTo(center + (target - center) * (step / 12f))
            release(MouseButton.Secondary)
        }
        waitForIdle()
        assertTrue(h.changes.isEmpty(), "a right-button drag patches nothing")
    }

    @Test
    fun shortBayScrollsButPlugDragWins() = runComposeUiTest {
        val h = bay(height = 200)
        waitForIdle()
        val before = centerOf("patch-out-LFO_A")
        // Straight down from a jack into empty space: the drag is the plug's, not the scroll's.
        dragTo("patch-out-LFO_A", before + Offset(0f, 120f))
        waitForIdle()
        assertEquals(before, centerOf("patch-out-LFO_A"), "the board did not scroll under a plug drag")
        assertTrue(h.changes.isEmpty())

        onNodeWithTag("patch-out-VOICE_FM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fmJackOnlyOnOsc() = runComposeUiTest {
        val h = bay(engine = OrpheusEngineId.OSC)
        onNodeWithTag("patch-in-FM-BOTH").assertExists()
        h.engine = OrpheusEngineId.VA
        waitForIdle()
        onNodeWithTag("patch-in-FM-BOTH").assertDoesNotExist()
        onNodeWithTag("patch-in-MORPH-BOTH").assertExists()
    }

    @Composable
    private fun ButtonHost(patch: DuoPatch = DuoPatch()) {
        OrpheusTheme {
            var p by remember { mutableStateOf(patch) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                PatchBayButton(
                    duoIndex = 0,
                    patch = p,
                    engine = OrpheusEngineId.VA,
                    onPatchChange = { p = it },
                    color = OrpheusColors.neonCyan,
                )
            }
        }
    }

    @Test
    fun patchButtonOpensPopoverWideAndSheetNarrow() {
        runComposeUiTest {
            setContent { ButtonHost() }
            onNodeWithTag("patch-button-0").performClick()
            onNodeWithTag("patch-panel-popover").assertExists()
            onNodeWithTag("patch-panel-sheet").assertDoesNotExist()
            val pitch = centerOf("patch-out-LFO_B").y - centerOf("patch-out-LFO_A").y
            assertTrue(pitch < 20f, "the popover keeps compact rows, got $pitch")
        }
        runComposeUiTest {
            setContent {
                val density = LocalDensity.current
                val narrow = remember(density) {
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = with(density) { IntSize(400.dp.roundToPx(), 800.dp.roundToPx()) }
                    }
                }
                CompositionLocalProvider(LocalWindowInfo provides narrow) { ButtonHost() }
            }
            onNodeWithTag("patch-button-0").performClick()
            onNodeWithTag("patch-panel-sheet").assertExists()
            onNodeWithTag("patch-panel-popover").assertDoesNotExist()
            val pitch = centerOf("patch-out-LFO_B").y - centerOf("patch-out-LFO_A").y
            assertTrue(pitch >= 29f, "the sheet has finger-sized rows, got $pitch")
        }
    }

    /** Keys go through the scene as a window sends them: the open bay eats letters and arrows, Esc closes it. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun typedKeyIsConsumedInBay() {
        var parentKeys = 0
        val scene = ImageComposeScene(1024, 768, Density(1f)) {
            val rootFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { rootFocus.requestFocus() }
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(rootFocus)
                    .focusable()
                    .onPreviewKeyEvent { parentKeys++; false },
            ) {
                ButtonHost(DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f))
            }
        }
        try {
            scene.render()
            scene.render()
            scene.type('x')
            assertTrue(parentKeys > 0, "control: with the bay closed the synth root gets keys")
            parentKeys = 0

            scene.tap(scene.node("patch-button-0"))
            scene.render()
            scene.render()
            var windowKeys = 0
            fun press(key: Key, codePoint: Int = 0) {
                if (!scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, codePoint = codePoint))) windowKeys++
                scene.render()
                if (!scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, codePoint = codePoint))) windowKeys++
                scene.render()
            }
            "asdfz".forEach { c -> press(Key(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code)), c.code) }
            press(Key.DirectionLeft)
            press(Key.DirectionRight)
            press(Key.DirectionUp)
            press(Key.DirectionDown)

            assertEquals(0, parentKeys, "an open bay eats every key")
            assertEquals(0, windowKeys, "no key leaves the bay for the window")
            scene.node("patch-out-LFO_A") // still open

            press(Key.Escape)
            assertTrue(scene.nodeOrNull("patch-out-LFO_A") == null, "Esc closes the bay")
        } finally {
            scene.close()
        }
    }

    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.nodeOrNull(tag: String): SemanticsNode? {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    }

    private fun ImageComposeScene.node(tag: String): SemanticsNode = nodeOrNull(tag) ?: error("no node $tag")

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.tap(node: SemanticsNode) {
        val at = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.type(c: Char) {
        val key = Key(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, codePoint = c.code)); render()
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, codePoint = c.code)); render()
    }
}

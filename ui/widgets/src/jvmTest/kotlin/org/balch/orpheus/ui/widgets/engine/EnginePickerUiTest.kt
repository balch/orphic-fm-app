package org.balch.orpheus.ui.widgets.engine

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EnginePickerUiTest {

    private val pulsar = EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true)

    private fun patch(engine: OrpheusEngineId, index: Int) =
        EngineChoice.Patch(EngineCatalog.patches.first { it.engine == engine && it.index == index })

    @Composable
    private fun Host(
        current: EngineChoice,
        onPick: (EngineChoice) -> Unit,
        controller: EnginePickerController = rememberEnginePickerController(),
    ) {
        OrpheusTheme {
            val recents = remember { EngineRecents(null, CoroutineScope(Dispatchers.Unconfined)) }
            CompositionLocalProvider(LocalEngineRecents provides recents) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    EnginePicker(
                        current = current,
                        choices = pulsar,
                        onPick = onPick,
                        color = OrpheusColors.neonCyan,
                        controller = controller,
                        modifier = Modifier.enginePickerTestTag("p"),
                    )
                }
            }
        }
    }

    @Test
    fun oneClickOpensOneClickPicks() = runComposeUiTest {
        var picked: EngineChoice? = null
        setContent { Host(current = patch(OrpheusEngineId.DX2, 4), onPick = { picked = it }) }

        onNodeWithTag("p").performClick()
        onNodeWithText("E piano 1").performClick()

        assertEquals(patch(OrpheusEngineId.DX2, 0), picked)
        assertTrue(onAllNodes(hasTestTagPrefix("p-tile-")).fetchSemanticsNodes().isEmpty(), "a pick closes the picker")
    }

    @Test
    fun enterWithNoMatchesPicksNothing() = runComposeUiTest {
        var picked: EngineChoice? = null
        setContent { Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = { picked = it }) }

        onNodeWithTag("p").performClick()
        onNodeWithTag("p-search").performTextInput("zzz")
        onNodeWithTag("p-search").performKeyInput { pressKey(Key.Enter) }

        assertNull(picked)
        onNodeWithText("No engines match").assertExists()
    }

    @Test
    fun enterWithBlankQueryPicksNothing() = runComposeUiTest {
        var picked: EngineChoice? = null
        setContent { Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = { picked = it }) }

        onNodeWithTag("p").performClick()
        onNodeWithTag("p-search").performClick().assertIsFocused()
        onNodeWithTag("p-search").performKeyInput { pressKey(Key.Enter) }

        assertNull(picked, "a blank search picks nothing, not the first tile")
        onNodeWithTag("p-search").assertExists()

        // Searching, Enter still takes the first match.
        onNodeWithTag("p-search").performTextInput("piano")
        onNodeWithTag("p-search").performKeyInput { pressKey(Key.Enter) }
        assertEquals(patch(OrpheusEngineId.DX2, 0), picked)
    }

    @Test
    fun narrowWindowShowsList() = runComposeUiTest {
        setContent {
            val density = LocalDensity.current
            val narrow = remember(density) {
                object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize = with(density) { IntSize(400.dp.roundToPx(), 800.dp.roundToPx()) }
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides narrow) {
                Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {})
            }
        }

        onNodeWithTag("p").performClick()

        onNodeWithTag("p-row-VA").assertExists()
        onNodeWithTag("p-tile-VA").assertDoesNotExist()
    }

    @Test
    fun controllerOpensOnCategory() = runComposeUiTest {
        lateinit var controller: EnginePickerController
        setContent {
            controller = rememberEnginePickerController()
            Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {}, controller = controller)
        }

        runOnIdle { controller.open(EngineCategory.DX_PATCHES) }

        onNodeWithTag("p-chip-DX_PATCHES").assertIsSelected()
        onNodeWithTag("p-tile-DX:0").assertExists()
    }

    @Test
    fun leavingWhileOpenClosesTheController() = runComposeUiTest {
        val controller = EnginePickerController()
        var shown by mutableStateOf(true)
        setContent {
            if (shown) Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {}, controller = controller)
        }

        runOnIdle { controller.open() }
        onNodeWithTag("p-search").assertExists()
        shown = false
        waitForIdle()
        assertTrue(!controller.isOpen, "a picker that leaves composition closes its controller")

        shown = true
        waitForIdle()
        onNodeWithTag("p-search").assertDoesNotExist()
    }

    /**
     * Keys go through the scene as a real window sends them, so this proves the open popover owns
     * them: the synth root's preview handler, focused underneath, never sees the search text.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun typingInSearchDoesNotReachParent() {
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
                Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {})
            }
        }
        try {
            scene.render()
            scene.render()
            scene.type('x')
            assertTrue(parentKeys > 0, "control: with the picker closed the synth root gets keys")
            parentKeys = 0

            scene.tap(scene.node("p"))
            scene.render()
            val search = scene.node("p-search")
            assertEquals(true, search.config.getOrNull(SemanticsProperties.Focused), "the search is focused on open")
            "piano".forEach { scene.type(it) }

            // A key the scene leaves unconsumed goes on to the window's onKeyEvent, where the DJ app
            // skips vibes on Left/Right. Walk into every edge and count what escapes.
            var windowKeys = 0
            fun press(key: Key, times: Int = 1) = repeat(times) {
                if (!scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))) windowKeys++
                scene.render()
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                scene.render()
            }
            press(Key.DirectionDown)
            assertEquals("p-tile-VA", scene.focusedTag())
            press(Key.DirectionLeft, 3)
            assertEquals("p-tile-WSH", scene.focusedTag(), "the first tile of the row")
            press(Key.DirectionUp)
            assertTrue(scene.focusedTag().orEmpty().startsWith("p-chip-"), "up from the tiles lands on a chip")
            press(Key.DirectionLeft, 12)
            assertEquals("p-chip-RECENT", scene.focusedTag(), "the left end of the chips")
            press(Key.DirectionDown)
            press(Key.DirectionRight, 8)
            press(Key.DirectionUp, 4)

            assertEquals(0, windowKeys, "no arrow leaves the picker, even at an edge")
            assertEquals(0, parentKeys, "an open picker eats every key")
            scene.node("p-search") // still open: the arrows stayed inside it
        } finally {
            scene.close()
        }
    }

    /** Down leaves the search for the current tile, arrows move between tiles, Enter picks, Esc closes. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun arrowsMoveAndEnterPicksTheFocusedTile() {
        var picked: EngineChoice? = null
        var expanded: Boolean? = null
        val scene = ImageComposeScene(1024, 768, Density(1f)) {
            OrpheusTheme {
                val recents = remember { EngineRecents(null, CoroutineScope(Dispatchers.Unconfined)) }
                CompositionLocalProvider(LocalEngineRecents provides recents) {
                    EnginePicker(
                        current = EngineChoice.Engine(OrpheusEngineId.VA),
                        choices = pulsar,
                        onPick = { picked = it },
                        color = OrpheusColors.neonCyan,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.enginePickerTestTag("p"),
                    )
                }
            }
        }
        try {
            scene.render()
            scene.tap(scene.node("p"))
            scene.render()
            assertEquals(true, expanded)
            assertEquals("p-search", scene.focusedTag())
            scene.key(Key.DirectionDown)
            assertEquals("p-tile-VA", scene.focusedTag())
            scene.key(Key.DirectionRight)
            assertEquals("p-tile-VCF", scene.focusedTag())
            scene.key(Key.Enter)
            assertEquals(EngineChoice.Engine(OrpheusEngineId.VCF), picked)
            assertEquals(false, expanded)

            picked = null
            scene.tap(scene.node("p"))
            scene.render()
            scene.key(Key.Escape)
            assertNull(picked)
            assertEquals(false, expanded)
        } finally {
            scene.close()
        }
    }

    /** A printable key pressed while a tile has focus moves focus to the search and lands there as text. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun typingOnAFocusedTileGoesToTheSearch() {
        val scene = ImageComposeScene(1024, 768, Density(1f)) {
            Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {})
        }
        try {
            scene.render()
            scene.render()
            scene.tap(scene.node("p"))
            scene.render()
            scene.key(Key.DirectionDown)
            assertEquals("p-tile-VA", scene.focusedTag())
            scene.type('d')
            assertEquals("p-search", scene.focusedTag(), "focus moves to the search")
            val text = scene.node("p-search").config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("d", text)
        } finally {
            scene.close()
        }
    }

    /** A mouse drag across the chip row scrolls it, and the chip under the press is not picked. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun mouseDragScrollsTheChipRow() {
        val scene = ImageComposeScene(1024, 768, Density(1f)) {
            Host(current = EngineChoice.Engine(OrpheusEngineId.VA), onPick = {})
        }
        try {
            scene.render()
            scene.render()
            scene.tap(scene.node("p"))
            scene.render()
            // The popover is its own layer, so its nodes are placed by window bounds.
            val startLeft = scene.node("p-chip-TEXTURE").boundsInWindow.left
            val from = scene.node("p-chip-HARMONIC").boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, from); scene.render()
            for (step in 1..10) {
                scene.sendPointerEvent(PointerEventType.Move, from.copy(x = from.x - step * 15f)); scene.render()
            }
            scene.sendPointerEvent(PointerEventType.Release, from.copy(x = from.x - 150f)); scene.render()
            assertTrue(scene.node("p-chip-TEXTURE").boundsInWindow.left < startLeft - 100f, "the row followed the drag")
            assertEquals(false, scene.node("p-chip-HARMONIC").config.getOrNull(SemanticsProperties.Selected))
        } finally {
            scene.close()
        }
    }

    private fun hasTestTagPrefix(prefix: String) =
        androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.node(tag: String): SemanticsNode {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    }

    /** The tagged node holding focus (the popover's own focusable root carries no tag). */
    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.focusedTag(): String? {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.tap(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); render()
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); render()
    }

    /** The key down and up a letter makes; these are what the synth's key handler plays notes from. */
    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.type(c: Char) {
        val key = Key(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, codePoint = c.code)); render()
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, codePoint = c.code)); render()
    }
}

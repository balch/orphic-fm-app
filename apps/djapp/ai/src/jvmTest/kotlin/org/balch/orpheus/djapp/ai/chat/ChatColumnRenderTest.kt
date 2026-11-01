package org.balch.orpheus.djapp.ai.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.ai.ActionKind
import org.balch.orpheus.djapp.ai.DjAiFeature
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.DjAiPanelActions
import org.balch.orpheus.djapp.ai.DjAiPhase
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.ideas.Idea
import org.balch.orpheus.ui.theme.OrpheusTheme

class ChatColumnRenderTest {
    private companion object {
        const val FOLD_SCALE = 0.652f
        const val W = 440
        const val H = 1000
        const val REPLY = "Pulled the bass down a touch."
    }

    private val state = DjAiUiState(
        isKeySet = true,
        phase = DjAiPhase.GENERATING,
        feed = listOf(
            DjAiFeedItem.Request(0, "A dusty midnight groove"),
            DjAiFeedItem.Reply(1, "$REPLY\n- **BASS** at -3 dB\n- pad wider"),
            DjAiFeedItem.Move(2, EditOrigin.USER, "PAD level", "0.0 dB", "+2.0 dB"),
            DjAiFeedItem.Action(3, ActionKind.OPEN_MIXER, "Open the Mixer so I can make that change."),
            DjAiFeedItem.Action(4, ActionKind.CONFIRM_WIPE, "This clears your mix: 2 changes.", answer = false),
        ),
        nextId = 5,
        queued = listOf("and darker"),
        ideas = listOf(
            Idea("Push the hats wider", "Push the hats wider", mix = true),
            Idea("Add a breakdown before the drop", "Add a breakdown before the drop", mix = false),
        ),
        ideasOpen = true,
    )

    @Test
    fun inputIsAtTheBottom() = scene {
        val input = bounds(CHAT_INPUT_TAG)
        assertTrue(H - input.bottom <= 24f, "the input ends ${H - input.bottom}dp above the column's bottom")
    }

    @Test
    fun replyTextReadsAtChatBody() = scene {
        val size = fontSizeOf(REPLY)
        val want = LegibleText.CHAT_BODY / FOLD_SCALE
        assertTrue(abs(size - want) < 0.1f, "reply at ${size}sp, want ${want}sp")
        assertTrue(abs(size - 23f) < 0.1f)
    }

    @Test
    fun ideasSitAboveFeed() = scene {
        val ideas = bounds(CHAT_IDEAS_TAG)
        val feed = bounds(CHAT_FEED_TAG)
        assertTrue(ideas.bottom <= feed.top, "ideas end at ${ideas.bottom}, the feed starts at ${feed.top}")
        assertTrue(texts().any { it == "Push the hats wider" })
    }

    @Test
    fun actionCardShowsBothButtons() = scene {
        for (label in listOf("Open Mixer", "Not now", "Clear All", "Keep my mix")) {
            assertTrue(texts().any { it == label }, "no $label button in ${texts()}")
        }
        // The answered card's buttons are off, and it says which was chosen.
        assertEquals(true, disabled("Keep my mix"))
        assertEquals(true, disabled("Clear All"))
        assertEquals(false, disabled("Open Mixer"))
        assertTrue(texts().any { it == "You chose: Keep my mix" }, "${texts()}")
    }

    @Test
    fun queuedMessageShowsAsPending() = scene {
        assertTrue(texts().any { it == "and darker" })
        assertTrue(texts().any { it == "queued" })
        // While a turn runs the button stops it.
        assertTrue(texts().any { it == "Stop" })
    }

    @Test
    fun sendIsOffWhileTheInputIsBlank() {
        scene(state.copy(phase = DjAiPhase.IDLE, queued = emptyList())) {
            assertEquals(true, disabled("Send"))
        }
        scene(state.copy(phase = DjAiPhase.IDLE, queued = emptyList(), promptDraft = "louder")) {
            assertEquals(false, disabled("Send"))
        }
    }

    private fun feature(s: DjAiUiState): DjAiFeature = object : DjAiFeature {
        override val stateFlow: StateFlow<DjAiUiState> = MutableStateFlow(s)
        override val actions = DjAiPanelActions.EMPTY
    }

    private fun scene(s: DjAiUiState = state, body: Rig.() -> Unit) {
        val scene = ImageComposeScene(W, H, Density(1f)) {
            CompositionLocalProvider(LocalCanvasScale provides FOLD_SCALE) {
                OrpheusTheme { ChatColumn(feature(s), Modifier.fillMaxSize(), showMixerToggle = true) }
            }
        }
        try {
            var now = 0L
            repeat(6) { now += 16; scene.render(now * 1_000_000) }
            Rig(scene).body()
        } finally {
            scene.close()
        }
    }

    private class Rig(private val scene: ImageComposeScene) {
        fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        }

        fun bounds(tag: String): Rect =
            assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, tag).boundsInRoot

        fun texts(): List<String> = nodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        private fun textNode(text: String): SemanticsNode = assertNotNull(
            nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true },
            "no text \"$text\"",
        )

        /** Whether the clickable that holds [text] is disabled, walking up from the text. */
        fun disabled(text: String): Boolean {
            var n: SemanticsNode? = textNode(text)
            while (n != null) {
                if (n.config.getOrNull(SemanticsActions.OnClick) != null || n.config.contains(SemanticsProperties.Disabled)) {
                    return n.config.contains(SemanticsProperties.Disabled)
                }
                n = n.parent
            }
            error("\"$text\" is not in a clickable")
        }

        fun fontSizeOf(startsWith: String): Float {
            val node = assertNotNull(
                nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(startsWith) } == true },
                "no text starting \"$startsWith\"",
            )
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
            return results.single().layoutInput.style.fontSize.value
        }
    }
}

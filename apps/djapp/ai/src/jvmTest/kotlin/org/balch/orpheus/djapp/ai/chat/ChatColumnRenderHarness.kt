package org.balch.orpheus.djapp.ai.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.ai.ActionKind
import org.balch.orpheus.djapp.ai.AiSheetMode
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.DjAiPhase
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.DjAiViewModel
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.ideas.Idea
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/** ./gradlew :apps:djapp:ai:jvmTest --tests '*ChatColumnRenderHarness*' -PrenderHarness --rerun */
class ChatColumnRenderHarness {
    private val state = DjAiUiState(
        isKeySet = true,
        availableModels = AiModel.entries,
        phase = DjAiPhase.GENERATING,
        sheetMode = AiSheetMode.MIXER,
        feed = listOf(
            DjAiFeedItem.Request(0, "A dusty midnight groove with warm tape hiss, slow and minor"),
            DjAiFeedItem.Thinking(1, "Choosing the key and tempo", "A minor at 92 BPM keeps it slow."),
            DjAiFeedItem.Tool(2, "Apply Vibe", running = false, summary = "pulsar_apply_vibe \"Midnight Tape\""),
            DjAiFeedItem.Reply(
                3,
                "Here is **Midnight Tape**. What changed:\n- a slow *minor* groove at 92 BPM\n- tape hiss on the pad\n" +
                    "- the bass sits under the kick\n\nTell me what to push.",
            ),
            DjAiFeedItem.Move(4, EditOrigin.USER, "PAD level", "0.0 dB", "+2.0 dB"),
            DjAiFeedItem.Request(5, "the bass is too loud now"),
            DjAiFeedItem.Tool(6, "Mix set", running = true, summary = "mix_set BASS −3.0 dB"),
            DjAiFeedItem.Action(7, ActionKind.OPEN_MIXER, "Open the Mixer so I can make that change."),
        ),
        nextId = 8,
        queued = listOf("and a little darker"),
        promptDraft = "maybe a breakdown",
        ideas = listOf(
            Idea("Pull the hats back so the snare speaks", "Pull the hats back", mix = true),
            Idea("Add a breakdown before the second drop, with only the pad and the bass", "Add a breakdown", mix = false),
        ),
    )

    private fun render(name: String, w: Int, h: Int, scale: Float, s: DjAiUiState = state, tapLabel: String? = null) {
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
            CompositionLocalProvider(LocalCanvasScale provides scale) {
                OrpheusTheme {
                    ChatColumn(
                        DjAiViewModel.previewFeature(s),
                        Modifier.fillMaxSize().background(OrpheusColors.deepPurple),
                        showMixerToggle = true,
                        onCollapse = {},
                    )
                }
            }
        }
        try {
            var now = 0L
            repeat(8) { now += 16; scene.render(now * 1_000_000) }
            if (tapLabel != null) {
                val nodes = mutableListOf<SemanticsNode>()
                fun walk(n: SemanticsNode) { nodes += n; n.children.forEach(::walk) }
                walk(scene.semanticsOwners.first().unmergedRootSemanticsNode)
                val at = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(tapLabel) == true }.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                repeat(8) { now += 16; scene.render(now * 1_000_000) }
            }
            val png = scene.render(now * 1_000_000).encodeToData()!!.bytes
            val out = File("build/djapp-render/chat").apply { mkdirs() }
            File(out, "$name.png").writeBytes(png)
            println("wrote ${File(out, "$name.png").absolutePath}")
        } finally {
            scene.close()
        }
    }

    @Test fun fold() = render("chat_fold_440x1000", 440, 1000, scale = 0.652f)

    @Test fun native() = render("chat_native_440x1000", 440, 1000, scale = 1f)

    // The settings popover over the chat.
    @Test fun settings() = render("chat_settings_440x1000", 440, 1000, scale = 0.652f, tapLabel = "Chat settings")

    @Test fun settingsNoKey() = render(
        "chat_settings_nokey_440x1000", 440, 1000, scale = 0.652f, state.copy(isKeySet = false), tapLabel = "Chat settings",
    )

    @Test fun empty() = render("chat_empty_440x1000", 440, 1000, scale = 0.652f, DjAiUiState(isKeySet = false))
}

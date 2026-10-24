package org.balch.orpheus.djapp.ai

import java.io.File
import kotlin.test.Test
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.ideas.Idea

/** ./gradlew :apps:djapp:ai:jvmTest --tests '*AiSheetRenderHarness*' -PrenderHarness --rerun */
class AiSheetRenderHarness {
    private val talk = DjAiUiState(
        isKeySet = true,
        promptDraft = "and a little more air on the hats",
        feed = listOf(
            DjAiFeedItem.Request(0, "A dusty midnight groove"),
            DjAiFeedItem.Reply(1, "Here is **Rust Belt Sunrise**: a slow kick, a warm pad and a bass that sits under it.\n- bass at -3 dB\n- pad a touch wider"),
            DjAiFeedItem.Move(2, EditOrigin.USER, "PAD level", "0.0 dB", "+2.0 dB"),
            DjAiFeedItem.Request(3, "Bring the bass up a little"),
            DjAiFeedItem.Tool(4, "Mix set", running = false, summary = "mix_set BASS -1.5 dB"),
            DjAiFeedItem.Reply(5, "Bass is up 1.5 dB. It still sits under the kick."),
        ),
        nextId = 6,
        ideas = listOf(
            Idea("Push the hats wider", "Push the hats wider", mix = true),
            Idea("Add a breakdown before the drop", "Add a breakdown before the drop", mix = false),
        ),
    )

    private fun render(name: String, canvas: SheetCanvas, mode: AiSheetMode, landscape: Boolean = true, interact: SheetScene.() -> Unit = {}) {
        val chat = FakeChat(talk.copy(sheetMode = mode))
        SheetScene(canvas) { AiSheet(chat, sheetMixer(), landscape, onDismiss = {}) }.use { s ->
            s.interact()
            val out = File("build/djapp-render/sheet").apply { mkdirs() }
            File(out, "$name.png").writeBytes(s.png())
            println("wrote ${File(out, "$name.png").absolutePath}")
        }
    }

    @Test fun mixerFold() = render("mixer_fold_1280x1153", SheetCanvas.FOLD, AiSheetMode.MIXER)

    @Test fun mixerDesktop() = render("mixer_desktop_1000x700", SheetCanvas.DESKTOP, AiSheetMode.MIXER)

    @Test fun chatDesktop() = render("chat_desktop_1280x800", SheetCanvas.DESKTOP_NATIVE, AiSheetMode.CHAT)

    @Test fun mixerFoldCollapsed() = render("mixer_fold_collapsed_1280x1153", SheetCanvas.FOLD, AiSheetMode.MIXER) { click("Collapse chat") }

    @Test fun mixerPhone() = render("mixer_phone_412x900", SheetCanvas.PHONE, AiSheetMode.MIXER, landscape = false)
}

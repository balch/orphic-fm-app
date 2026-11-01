package org.balch.orpheus.djapp.ai.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.DjAiPanelActions
import org.balch.orpheus.djapp.ai.DjAiPhase
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.DraftEdit
import org.balch.orpheus.djapp.ai.SongPasteRef
import org.balch.orpheus.djapp.ai.canSend
import org.balch.orpheus.djapp.ai.classifyDraftEdit

private const val INPUT_MAX_LINES = 5

/** The chat input's focus, shared through [LocalChatInputFocus] so an overlay anywhere in the sheet can hand it back. */
internal class ChatInputFocus {
    val requester = FocusRequester()
    var focused = false
}

internal val LocalChatInputFocus = staticCompositionLocalOf<ChatInputFocus?> { null }

/**
 * Focus for an overlay that takes it on open (the chat settings, the Mixer's changes): closing hands it back to the
 * chat input if the input had it. Esc closes the overlay on key-down, and focus waits on [host] for the key-up,
 * which is consumed too, so neither half reaches the sheet.
 */
internal class OverlayFocus(private val input: ChatInputFocus?) {
    val host = FocusRequester()
    private var restore = false
    private var escUp = false

    fun onOpen() {
        restore = input?.focused == true
    }

    fun onClose() {
        if (restore) input?.requester?.requestFocus()
        restore = false
    }

    /** For the host's preview key handler; [close] only hides the overlay. */
    fun onPreviewKey(e: KeyEvent, open: Boolean, close: () -> Unit): Boolean {
        if (e.key != Key.Escape) return false
        if (e.type == KeyEventType.KeyUp && escUp) {
            escUp = false
            onClose()
            return true
        }
        if (!open) return false
        if (e.type == KeyEventType.KeyDown) {
            host.requestFocus()
            escUp = true
            close()
        }
        return true
    }
}

@Composable
internal fun rememberOverlayFocus(): OverlayFocus {
    val input = LocalChatInputFocus.current
    return remember(input) { OverlayFocus(input) }
}

/**
 * The input pinned at the bottom: the song reference chip, a field that grows to [INPUT_MAX_LINES] lines, and
 * Send (Stop while a turn runs). Enter sends, Shift+Enter adds a line; typing during a turn queues.
 */
@Composable
internal fun ChatInput(state: DjAiUiState, actions: DjAiPanelActions, modifier: Modifier = Modifier) {
    val focus = LocalChatInputFocus.current
    // Leaving composition (a collapsed chat) may send no focus event; never hand focus back to a detached field.
    DisposableEffect(focus) { onDispose { focus?.focused = false } }
    val running = state.phase != DjAiPhase.IDLE
    // The field keeps its own cursor; a draft changed from outside (sent, or handed back by a stop) replaces it.
    var field by remember { mutableStateOf(TextFieldValue(state.promptDraft, TextRange(state.promptDraft.length))) }
    val seenDraft = remember { arrayOf(state.promptDraft) }
    if (state.promptDraft != seenDraft[0]) {
        seenDraft[0] = state.promptDraft
        if (state.promptDraft != field.text) field = TextFieldValue(state.promptDraft, TextRange(state.promptDraft.length))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // The draft read from the field, which can be a frame ahead of the state.
    val sendable = state.copy(promptDraft = field.text).canSend()
    val submit = {
        if (sendable) {
            actions.submit()
            keyboard?.hide()
        }
    }
    fun newLine() {
        val sel = field.selection
        val text = field.text.replaceRange(sel.min, sel.max, "\n")
        field = TextFieldValue(text, TextRange(sel.min + 1))
        actions.updateDraft(text)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.reference?.let { ReferenceChip(it, actions.detachReference) }
        if (state.parsing != null && state.reference == null) {
            Text("Reading the paste…", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim))
        }
        val shape = RoundedCornerShape(12.dp)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ChatInk.field)
                .border(1.dp, ChatInk.line, shape)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            val textStyle = chatStyle(LegibleText.CHAT_BODY)
            // IMEs send Enter four ways (onSend, onDone/onGo, a committed "\n", a hardware key); each ends in submit.
            BasicTextField(
                value = field,
                onValueChange = { new ->
                    when (val edit = classifyDraftEdit(field.text, new.text)) {
                        is DraftEdit.Reference -> actions.attachReference(edit.pasted)
                        is DraftEdit.Text -> {
                            field = if (edit.draft == new.text) new else TextFieldValue(edit.draft, TextRange(edit.draft.length))
                            actions.updateDraft(edit.draft)
                            if (edit.submit) submit()
                        }
                    }
                },
                maxLines = INPUT_MAX_LINES,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }, onDone = { submit() }, onGo = { submit() }),
                textStyle = textStyle,
                cursorBrush = SolidColor(ChatInk.accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .then(
                        if (focus == null) Modifier
                        else Modifier.focusRequester(focus.requester).onFocusChanged { focus.focused = it.isFocused },
                    )
                    .onPreviewKeyEvent { e ->
                        if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) if (e.isShiftPressed) newLine() else submit()
                        true
                    }
                    // Keys the field leaves (an arrow at the text's edge) stop here, so the app's shortcuts never
                    // see typing; Esc and Tab still close the sheet and move focus.
                    .onKeyEvent { e -> e.key != Key.Escape && e.key != Key.Tab },
                decorationBox = { inner ->
                    Box {
                        if (field.text.isEmpty()) {
                            Text(
                                when {
                                    !state.isKeySet -> "Add an API key first"
                                    running -> "Type to queue your next message…"
                                    else -> "Ask for a vibe, or what to change…"
                                },
                                style = textStyle.copy(color = ChatInk.dim.copy(alpha = 0.7f)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (running) {
                ChatButton("Stop", actions.cancel, color = ChatInk.warn)
            } else {
                ChatButton("Send", submit, enabled = sendable, primary = true)
            }
        }
    }
}

/** The attached song reference over the field, with ✕ to drop it. */
@Composable
private fun ReferenceChip(reference: SongPasteRef, onDetach: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ChatInk.field)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            "♪ ${reference.title ?: "Song reference"} · ${reference.lines} lines",
            style = chatStyle(LegibleText.SECONDARY),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "✕",
            style = chatStyle(LegibleText.SECONDARY, ChatInk.dim),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDetach).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

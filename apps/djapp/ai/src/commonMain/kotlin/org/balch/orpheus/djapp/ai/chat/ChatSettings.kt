package org.balch.orpheus.djapp.ai.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.DjAiPanelActions
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.copyPlainText
import org.balch.orpheus.djapp.ai.mcp.McpEndpoint
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.ai.deriveAiProviderFromKey

/** The header's settings: model, API key, how a new vibe is cast, and the MCP endpoint. */
@Composable
internal fun ChatSettings(state: DjAiUiState, actions: DjAiPanelActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .background(ChatInk.card)
            .border(1.dp, ChatInk.line, shape)
            // Its own taps stay here rather than reaching the close-on-tap scrim under it; no semantics, so no unlabeled button.
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = chatStyle(LegibleText.CHAT_BODY, weight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            ChatButton("Close", onClose)
        }
        Section("Model") {
            for (model in state.availableModels) {
                Choice(model.displayName, selected = model == state.selectedModel) {
                    if (model != state.selectedModel) {
                        actions.selectModel(model)
                        onClose()
                    }
                }
            }
        }
        Section("API key") {
            val provider = state.selectedModel.aiProvider
            if (state.isKeySet) KeySaved(provider, actions.clearKey) else KeyEntry(provider, actions.saveKey)
        }
        Section("New vibes cast") {
            Choice("Bed fixed", selected = state.castMode == CastMode.BED_FIXED) { actions.selectCastMode(CastMode.BED_FIXED) }
            Choice("Everything rolled", selected = state.castMode == CastMode.ALL_ROLLED) { actions.selectCastMode(CastMode.ALL_ROLLED) }
        }
        state.mcpEndpoint?.let { Section("MCP") { McpEndpointRow(it) } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = chatStyle(LegibleText.SECONDARY, ChatInk.dim, FontWeight.SemiBold))
        content()
    }
}

/** A radio row: a ring that fills when [selected]. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ChatInk.accent.copy(alpha = 0.15f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = legibleDp(5.dp)),
    ) {
        Box(
            Modifier
                .size(legibleDp(12.dp))
                .clip(CircleShape)
                .border(1.5.dp, if (selected) ChatInk.accent else ChatInk.dim, CircleShape)
                .padding(legibleDp(3.dp))
                .clip(CircleShape)
                .background(if (selected) ChatInk.accent else Color.Transparent),
        )
        Text(label, style = chatStyle(LegibleText.SECONDARY, if (selected) ChatInk.text else ChatInk.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The saved key's provider, with Remove. */
@Composable
private fun KeySaved(provider: AiProvider, onRemove: (AiProvider) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Key saved for ${provider.displayName}", style = chatStyle(LegibleText.SECONDARY), modifier = Modifier.weight(1f))
        ChatButton("Remove", { onRemove(provider) })
    }
}

/** A hidden field for a pasted key; Save picks the provider from the key's shape. */
@Composable
private fun KeyEntry(provider: AiProvider, onSave: (AiProvider, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val save = {
        val derived = deriveAiProviderFromKey(key)
        if (derived != null) onSave(derived, key) else error = "That doesn't look like an API key"
    }
    val style = chatStyle(LegibleText.SECONDARY)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = key,
            onValueChange = { key = it; error = null },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ChatInk.accent),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ChatInk.field)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                // As in the chat input: keys the field leaves never reach the app's shortcuts.
                .onKeyEvent { e -> e.key != Key.Escape && e.key != Key.Tab },
            decorationBox = { inner ->
                Box {
                    if (key.isEmpty()) Text("Paste a key for ${provider.displayName}", style = style.copy(color = ChatInk.dim.copy(alpha = 0.7f)))
                    inner()
                }
            },
        )
        error?.let { Text(it, style = chatStyle(LegibleText.SECONDARY, ChatInk.warn)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton("Save", save, enabled = key.isNotBlank(), primary = true)
            ChatButton(if (show) "Hide" else "Show", { show = !show })
            keyPage(provider)?.let { url -> ChatButton("Get a key", { uriHandler.openUri(url) }) }
        }
    }
}

private fun keyPage(provider: AiProvider): String? = when (provider) {
    AiProvider.Google -> "https://aistudio.google.com/apikey"
    AiProvider.Anthropic -> "https://console.anthropic.com/settings/keys"
    AiProvider.OpenAI -> null
}

/** The MCP url and a copy button for its bearer token, which flips to a check for a moment. */
@Composable
private fun McpEndpointRow(endpoint: McpEndpoint) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ChatInk.field)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(endpoint.url, style = chatStyle(LegibleText.SECONDARY), modifier = Modifier.weight(1f))
        ChatButton(if (copied) "Copied" else "Copy token", {
            scope.launch { clipboard.copyPlainText(endpoint.token, sensitive = true) }
            copied = true
        })
    }
}

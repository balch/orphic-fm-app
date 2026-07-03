package org.balch.orpheus.features.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors

@Immutable
data class VibeCreatePanelActions(
    val updateDraft: (String) -> Unit = {},
    val submit: () -> Unit = {},
    val reset: () -> Unit = {},
) {
    companion object { val EMPTY = VibeCreatePanelActions() }
}

interface VibeCreateFeature : SynthFeature<VibeCreateUiState, VibeCreatePanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.VIBE_CREATE
            override val title = "Vibe Create"
            override val markdown = "Create a Pulsar vibe with the AI and watch it work."
            override val portControlKeys = emptyMap<String, String>()
        }
    }
}

@Composable
fun VibeCreatePanel(
    feature: VibeCreateFeature = VibeCreateViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    // Signals the window-level SynthKeyboardHandler to stop playing notes while the prompt field is
    // focused (sets isDialogActive) — same mechanism SpeechPanel uses. Without it, typing plays the synth.
    onDialogActiveChange: (Boolean) -> Unit = {},
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "VIBE",
        color = OrpheusColors.neonCyan,
        expandedTitle = uiState.vibe?.name ?: "AI Vibe",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            uiState.error?.let {
                Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = OrpheusColors.warmGlow)
                Text(
                    "try again ↻",
                    style = MaterialTheme.typography.labelMedium,
                    color = OrpheusColors.neonCyan,
                    modifier = Modifier.clickable { actions.reset() }.padding(vertical = 2.dp),
                )
            }
            when (uiState.phase) {
                VibePhase.IDLE -> IdleContent(uiState.promptDraft, actions, onDialogActiveChange)
                VibePhase.GENERATING -> GeneratingContent(uiState.feed, actions)
                VibePhase.RESULT -> uiState.vibe?.let { ResultContent(it, actions) }
            }
        }
    }
}

@Composable
private fun IdleContent(draft: String, actions: VibeCreatePanelActions, onDialogActiveChange: (Boolean) -> Unit) {
    Text("Describe a vibe and I'll build it:", style = MaterialTheme.typography.labelMedium, color = OrpheusColors.onSurfaceVariantDark)
    BasicTextField(
        value = draft,
        onValueChange = actions.updateDraft,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { actions.submit() }),
        textStyle = TextStyle(fontSize = 13.sp, color = OrpheusColors.pureWhite),
        cursorBrush = SolidColor(OrpheusColors.neonCyan),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { onDialogActiveChange(it.isFocused) }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                    !event.isShiftPressed
                ) {
                    actions.submit()
                    true
                } else false
            },
        decorationBox = { inner ->
            Box {
                if (draft.isEmpty()) {
                    Text(
                        "a slow brooding heartland ballad",
                        style = TextStyle(fontSize = 13.sp, color = OrpheusColors.onSurfaceVariantDark.copy(alpha = 0.5f)),
                    )
                }
                inner()
            }
        },
    )
    Text(
        "Create ▶",
        style = MaterialTheme.typography.labelLarge,
        color = OrpheusColors.neonCyan,
        modifier = Modifier.clickable { actions.submit() }.padding(vertical = 4.dp),
    )
}

@Composable
private fun GeneratingContent(feed: List<String>, actions: VibeCreatePanelActions) {
    Text("Generating…", style = MaterialTheme.typography.labelLarge, color = OrpheusColors.neonCyan)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
    ) {
        feed.forEach { line ->
            val isThought = line.startsWith("💭")
            Text(
                line,
                style = MaterialTheme.typography.labelSmall.let {
                    if (isThought) it.copy(fontStyle = FontStyle.Italic) else it
                },
                color = if (isThought) OrpheusColors.neonCyan.copy(alpha = 0.6f) else OrpheusColors.onSurfaceVariantDark,
            )
        }
    }
    // Always offer an escape: if a run stalls (agent talks instead of building, or never finishes),
    // the panel must not be stuck on the spinner with no way back.
    Text(
        "cancel ↻",
        style = MaterialTheme.typography.labelMedium,
        color = OrpheusColors.neonCyan,
        modifier = Modifier.clickable { actions.reset() }.padding(top = 4.dp),
    )
}

@Composable
private fun ResultContent(vibe: Vibe, actions: VibeCreatePanelActions) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(vibe.name, style = MaterialTheme.typography.titleMedium, color = OrpheusColors.neonCyan)
        Text(
            "${vibe.bpm.toInt()} bpm · ${vibe.rootNote.name} ${vibe.scaleType.name}",
            style = MaterialTheme.typography.labelMedium, color = OrpheusColors.pureWhite,
        )
        Text(
            "genre: ${vibe.genre.progressionStyle.name} · ${vibe.genre.chordsPerBar} chord/bar · swing ${(vibe.genre.swingAmount * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, color = OrpheusColors.onSurfaceVariantDark,
        )
        vibe.arrangement?.sections?.takeIf { it.isNotEmpty() }?.let { sections ->
            SectionHeader("sections")
            Text(sections.joinToString(" → ") { it.name }, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.pureWhite)
        }
        SectionHeader("instruments")
        instrumentLines(vibe).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.pureWhite) }
        vibe.lick?.let { lick ->
            SectionHeader("lick")
            Text("${lick.steps.size} steps · octave ${vibe.lickOctave}", style = MaterialTheme.typography.labelSmall, color = OrpheusColors.pureWhite)
        }
        vibe.band?.members?.takeIf { it.isNotEmpty() }?.let { members ->
            SectionHeader("band")
            Text(members.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.pureWhite)
        }
        SectionHeader("effects")
        Text(
            "reverb ${(vibe.effects.reverbSize * 100).toInt()}% · delay fb ${(vibe.effects.delayFeedback * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, color = OrpheusColors.onSurfaceVariantDark,
        )
        Text(
            "new vibe ↻",
            style = MaterialTheme.typography.labelLarge,
            color = OrpheusColors.neonCyan,
            modifier = Modifier.clickable { actions.reset() }.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = OrpheusColors.neonCyan.copy(alpha = 0.7f))
}

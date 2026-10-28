package org.balch.orpheus.djapp.vibepresets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.balch.orpheus.djapp.AdaptiveSheet
import org.balch.orpheus.ui.theme.OrpheusColors

internal const val REPLACE_PREFIX = "Replace "

private val RowShape = RoundedCornerShape(8.dp)

/**
 * The capture sheet while a draft is open, and the manage sheet while [manageOpen]. Hosted beside
 * the stage, not inside the bar, so a sheet covers the panels the way the other sheets do.
 */
@Composable
internal fun VibePresetSheets(
    feature: VibePresetsFeature,
    isLandscape: Boolean,
    manageOpen: Boolean,
    onManageDismiss: () -> Unit,
) {
    val state by feature.stateFlow.collectAsStateWithLifecycle()
    val actions = feature.actions
    val draft = state.draft
    if (draft != null) {
        CapturePresetSheet(
            draft = draft,
            vibeName = state.vibeName,
            presets = state.presets,
            isLandscape = isLandscape,
            onCancel = actions.cancelCapture,
            onSave = actions.saveCapture,
        )
    } else if (manageOpen) {
        ManagePresetsSheet(state = state, actions = actions, isLandscape = isLandscape, onDismiss = onManageDismiss)
    }
}

/**
 * Name the capture (pre-filled with the suggestion), see what changed, and when the book is full
 * pick the preset to replace.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CapturePresetSheet(
    draft: CaptureDraft,
    vibeName: String,
    presets: List<PresetRow>,
    isLandscape: Boolean,
    onCancel: () -> Unit,
    onSave: (name: String, replaceId: Int?) -> Unit,
) {
    var name by remember(draft) { mutableStateOf(draft.suggestedName) }
    var replaceId by remember(draft) { mutableStateOf(draft.replaceDefaultId) }
    val canSave = name.isNotBlank() && (!draft.full || presets.any { it.id == replaceId })
    AdaptiveSheet(
        isLandscape = isLandscape,
        portraitPeekHeight = if (draft.full) 460.dp else 300.dp,
        onDismiss = onCancel,
    ) {
        SheetColumn {
            SheetTitle("CAPTURE PRESET")
            SheetSubtitle("$vibeName · ${draft.count} of $MAX_PRESETS")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = OrpheusColors.onSurfaceDark),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PresetTeal,
                    unfocusedBorderColor = PresetTeal.copy(alpha = 0.6f),
                    cursorColor = PresetTeal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    draft.chips.forEach { chip ->
                        Text(
                            text = chip,
                            color = OrpheusColors.silverGrey,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(OrpheusColors.softPurple)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (draft.full) {
                val nowMs = rememberNowMs()
                Text(
                    text = "Replace",
                    color = PresetAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                presets.forEach { preset ->
                    val chosen = preset.id == replaceId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .focusRing(RowShape)
                            .semantics { contentDescription = REPLACE_PREFIX + preset.name }
                            .selectable(selected = chosen, role = Role.RadioButton) { replaceId = preset.id },
                    ) {
                        RadioButton(
                            selected = chosen,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = PresetAmber,
                                unselectedColor = PresetMuted,
                            ),
                            modifier = Modifier.padding(6.dp),
                        )
                        RowName(preset.name, color = OrpheusColors.onSurfaceDark)
                        RowTime(preset.timeLabel(nowMs))
                    }
                }
            }
            SheetButtons {
                PresetPill("CANCEL", PresetPurple, onClick = onCancel)
                PresetPill(
                    text = "SAVE",
                    color = PresetTeal,
                    enabled = canSave,
                    onClick = { onSave(name.trim(), if (draft.full) replaceId else null) },
                )
            }
        }
    }
}

/**
 * The vibe's presets and its original. LOAD, RENAME and DELETE act on the highlighted row, which
 * starts at the selected preset; DELETE asks again before it deletes.
 */
@Composable
internal fun ManagePresetsSheet(
    state: VibePresetsUiState,
    actions: VibePresetsActions,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
) {
    // Null is the vibe's original.
    var highlighted by remember { mutableStateOf(state.selectedId) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = state.presets.firstOrNull { it.id == highlighted }
    val nowMs = rememberNowMs()

    fun highlight(id: Int?) {
        highlighted = id
        renaming = null
        confirmDelete = false
    }

    AdaptiveSheet(isLandscape = isLandscape, portraitPeekHeight = 400.dp, onDismiss = onDismiss) {
        SheetColumn {
            SheetTitle("PRESETS · ${state.vibeName.uppercase()}")
            SheetSubtitle("${state.presets.size} of $MAX_PRESETS")
            state.presets.forEach { preset ->
                ManageRow(highlighted = preset.id == target?.id, onClick = { highlight(preset.id) }) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (preset.id == state.selectedId) PresetTeal else Color.Transparent)
                            .ringIf(preset.id != state.selectedId),
                    )
                    val editing = renaming
                    if (editing != null && preset.id == target?.id) {
                        OutlinedTextField(
                            value = editing,
                            onValueChange = { renaming = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = OrpheusColors.onSurfaceDark),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PresetPurple,
                                unfocusedBorderColor = PresetPurple.copy(alpha = 0.6f),
                                cursorColor = PresetPurple,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        RowName(preset.name, color = OrpheusColors.onSurfaceDark)
                    }
                    RowTime(preset.timeLabel(nowMs))
                }
            }
            ManageRow(highlighted = target == null, onClick = { highlight(null) }) {
                Spacer(Modifier.size(12.dp))
                RowName(ORIGINAL_LABEL, color = PresetMuted)
            }
            SheetButtons {
                val editing = renaming
                if (target != null && editing != null) {
                    PresetPill("CANCEL", PresetPurple, onClick = { renaming = null })
                    PresetPill("SAVE", PresetTeal, enabled = editing.isNotBlank()) {
                        actions.rename(target.id, editing.trim())
                        renaming = null
                    }
                } else {
                    PresetPill(
                        text = if (confirmDelete) "DELETE?" else "DELETE",
                        color = OrpheusColors.mixerDistRed,
                        enabled = target != null,
                        filled = confirmDelete,
                    ) {
                        val id = target?.id ?: return@PresetPill
                        if (confirmDelete) {
                            actions.delete(id)
                            highlight(null)
                        } else {
                            confirmDelete = true
                        }
                    }
                    PresetPill("RENAME", PresetPurple, enabled = target != null) {
                        confirmDelete = false
                        renaming = target?.name
                    }
                    PresetPill("LOAD", PresetTeal) {
                        if (target != null) actions.select(target.id) else actions.loadOriginal()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = OrpheusColors.onSurfaceDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SheetSubtitle(text: String) {
    Text(
        text = text,
        color = OrpheusColors.greyText,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun SheetButtons(content: @Composable RowScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        content = content,
    )
}

@Composable
private fun ManageRow(highlighted: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (highlighted) PresetTeal.copy(alpha = 0.12f) else Color.Transparent)
            .focusRing(RowShape)
            .semantics { selected = highlighted }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.RowName(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RowTime(text: String) {
    Text(
        text = text,
        color = OrpheusColors.greyText,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier.padding(end = 4.dp),
    )
}

private fun Modifier.ringIf(ring: Boolean): Modifier =
    if (ring) border(2.dp, PresetTeal, CircleShape) else this

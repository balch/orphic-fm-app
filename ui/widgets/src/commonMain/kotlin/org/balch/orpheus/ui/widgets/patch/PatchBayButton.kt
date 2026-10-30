package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.popover.AnchoredPopoverOrSheet

private val PanelShape = RoundedCornerShape(10.dp)

/**
 * The duo panel's PATCH button. It opens [PatchBay] for duo [duoIndex] (0-5) in a popover on wide
 * windows and a bottom sheet on narrow ones. [compact] is the phone card's smaller pill.
 */
@Composable
fun PatchBayButton(
    duoIndex: Int,
    patch: DuoPatch,
    engine: OrpheusEngineId,
    onPatchChange: (DuoPatch) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val live = patch.cables.count { !it.parked }
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(if (compact) 22.dp else 26.dp)
                .clip(shape)
                .background(color.copy(alpha = if (open) 0.32f else 0.15f))
                .border(if (open) 1.5.dp else 1.dp, color.copy(alpha = if (open) 0.9f else 0.45f), shape)
                .clickable(role = Role.Button, onClickLabel = "Open patch bay") { open = true }
                .testTag("patch-button-$duoIndex")
                .padding(horizontal = if (compact) 6.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "PATCH",
                color = color,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            if (live > 0) {
                Box(Modifier.size(if (compact) 4.dp else 5.dp).clip(CircleShape).background(color))
            }
        }
        if (open) {
            AnchoredPopoverOrSheet(
                onDismiss = { open = false },
                popoverWidth = 560.dp,
                popoverHeight = 600.dp,
                sheetHeightFraction = 0.85f,
            ) { sheet, sizeModifier ->
                PatchBayPanel(
                    duoIndex = duoIndex,
                    patch = patch,
                    engine = engine,
                    onPatchChange = onPatchChange,
                    onDismiss = { open = false },
                    roomy = sheet,
                    modifier = sizeModifier.testTag(if (sheet) "patch-panel-sheet" else "patch-panel-popover"),
                )
            }
        }
    }
}

/**
 * The open bay with its title. Keys stop here as in the engine picker: Esc closes, arrows move
 * focus, and every other key is eaten so typing never plays the synth underneath.
 */
@Composable
private fun PatchBayPanel(
    duoIndex: Int,
    patch: DuoPatch,
    engine: OrpheusEngineId,
    onPatchChange: (DuoPatch) -> Unit,
    onDismiss: () -> Unit,
    roomy: Boolean,
    modifier: Modifier,
) {
    val rootFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }
    Column(
        modifier
            .clip(PanelShape)
            .background(OrpheusColors.pickerSurface)
            .border(1.dp, OrpheusColors.pickerOutline, PanelShape)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.Tab -> return@onKeyEvent false
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> null
                }
                if (direction != null && event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                // Consumed even when unused: a key that leaves reaches the synth's note and vibe hooks.
                true
            }
            .focusRequester(rootFocus)
            .focusable()
            .padding(10.dp),
    ) {
        val voices = "${duoIndex * 2 + 1}-${duoIndex * 2 + 2}"
        Text(
            text = "PATCH · Duo $voices · ${EngineCatalog.labelFor(EngineChoice.Engine(engine))}",
            color = OrpheusColors.pickerSection,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        PatchBay(patch, engine, onPatchChange, Modifier.fillMaxWidth().weight(1f, fill = false), roomy = roomy)
    }
}

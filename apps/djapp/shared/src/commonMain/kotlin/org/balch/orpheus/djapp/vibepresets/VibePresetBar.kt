package org.balch.orpheus.djapp.vibepresets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.TvGlassEnabled
import org.balch.orpheus.ui.infrastructure.panelGlassChrome
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.widgets.DropdownIconSize
import org.balch.orpheus.ui.widgets.LabeledDropdown
import kotlin.time.Clock

internal val PresetTeal = OrpheusColors.krakenTeal
internal val PresetAmber = OrpheusColors.mixerFxAmber
internal val PresetPurple = OrpheusColors.mixerMasterPurple
internal val PresetMuted = OrpheusColors.seahawksGrey

internal const val CAPTURE_LABEL = "◆ CAPTURE"
internal const val MANAGE_LABEL = "≡"
internal const val MANAGE_DESCRIPTION = "Manage presets"
internal const val PRESETS_TOGGLE_LABEL = "SAVES"
internal const val PRESET_DOT_PREFIX = "Preset "
internal const val NOW_DOT_DESCRIPTION = "Unsaved changes"
internal const val DISCARD_PROMPT = "Discard changes?"
internal const val DISCARD_LABEL = "DISCARD"
internal const val CANCEL_LABEL = "CANCEL"
internal const val SHOW_SAVES_DESCRIPTION = "Show saves"
internal const val HIDE_SAVES_DESCRIPTION = "Hide saves"

/** The vibe's original, e.g. "Bell Tolls - OG". */
internal fun originalLabel(vibeName: String): String = "$vibeName - OG"

private val DotSize = 14.dp
// Wider than the dot, so a thumb hits it; the joining line runs through the gap.
private val DotCellSize = 28.dp
// The panels' corner (CollapsibleColumnPanel).
private val BarShape = RoundedCornerShape(8.dp)
private val PillShape = RoundedCornerShape(6.dp)

/** A dot tap waiting on "Discard changes?": a preset's id, or null for the vibe's original. */
private data class PendingLoad(val presetId: Int?)

/** The bits of preset state the screen chrome reads; `changed` flips on every knob turn, so it is left out. */
internal data class PresetChrome(val available: Boolean, val barShown: Boolean, val draftOpen: Boolean)

@Composable
internal fun VibePresetsFeature.collectChrome(): State<PresetChrome> {
    fun chromeOf(s: VibePresetsUiState) = PresetChrome(s.available, s.barShown, s.draft != null)
    return remember(this) { stateFlow.map(::chromeOf).distinctUntilChanged() }
        .collectAsStateWithLifecycle(chromeOf(stateFlow.value))
}

/** Wall-clock time for the presets' age labels, re-read once a minute. */
@Composable
internal fun rememberNowMs(): Long {
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(60_000L)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

internal fun PresetRow.timeLabel(nowMs: Long): String =
    presetTimeLabel(capturedAtMs, nowMs, TimeZone.currentSystemDefault())

/**
 * The preset bar: the vibe's original (purple) always first, then a dot per preset joined by a line,
 * a dashed now-dot while the sound differs from the selected one, ≡ and CAPTURE. A tap on a dot
 * loads it, asking first while there are unsaved changes; ≡ opens [onManage]'s sheet.
 */
@Composable
internal fun VibePresetBar(
    feature: VibePresetsFeature,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by feature.stateFlow.collectAsStateWithLifecycle()
    val actions = feature.actions
    val presets = state.presets
    val selected = presets.firstOrNull { it.id == state.selectedId }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Vertical only: the panels above draw their glass to their slot's edges, so the bar does too.
            .padding(vertical = 4.dp)
            // The same glass the docked panels wear, tinted teal; flat on TV hardware like they are.
            .panelGlassChrome(
                liquidState = LocalLiquidState.current,
                effects = LocalLiquidEffects.current,
                color = PresetTeal,
                shape = BarShape,
                accented = true,
                glassEnabled = !LocalTelevisionHardware.current || TvGlassEnabled,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // A dot tap that would throw away unsaved changes asks first; a vibe switch or a save drops
        // the question.
        var asked by remember(state.vibeName) { mutableStateOf<PendingLoad?>(null) }
        val pending = asked?.takeIf { state.changed }
        fun load(target: PendingLoad) {
            val id = target.presetId
            if (id == null) actions.loadOriginal() else actions.select(id)
        }
        fun tap(target: PendingLoad) {
            if (state.changed) asked = target else load(target)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PresetDot(
                description = originalLabel(state.vibeName),
                color = PresetPurple,
                selected = selected == null,
                joinStart = false,
                joinEnd = presets.isNotEmpty() || state.changed,
                onClick = { if (state.changed || selected != null) tap(PendingLoad(null)) },
            )
            presets.forEachIndexed { i, preset ->
                PresetDot(
                    description = PRESET_DOT_PREFIX + preset.name,
                    color = PresetTeal,
                    selected = preset.id == state.selectedId,
                    joinStart = true,
                    joinEnd = i < presets.lastIndex || state.changed,
                    onClick = { tap(PendingLoad(preset.id)) },
                )
            }
            if (state.changed) NowDot(joinStart = true)
            Spacer(Modifier.weight(1f))
            PresetPill(
                text = MANAGE_LABEL,
                color = PresetPurple,
                modifier = Modifier.semantics { contentDescription = MANAGE_DESCRIPTION },
                onClick = onManage,
            )
            Spacer(Modifier.width(6.dp))
            PresetPill(CAPTURE_LABEL, PresetTeal, onClick = actions.openCapture)
        }
        Row(
            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending != null) {
                Text(
                    text = DISCARD_PROMPT,
                    color = PresetAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                PresetPill(CANCEL_LABEL, PresetPurple) { asked = null }
                PresetPill(DISCARD_LABEL, PresetAmber) {
                    asked = null
                    load(pending)
                }
            } else {
                Text(
                    text = selected?.name ?: originalLabel(state.vibeName),
                    color = if (selected != null) PresetTeal else PresetPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected != null) {
                    val time = selected.timeLabel(rememberNowMs())
                    Text("· $time", color = PresetMuted, fontSize = 10.sp, maxLines = 1)
                }
                if (state.changed) Text("· changed", color = PresetAmber, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PresetDot(
    description: String,
    color: Color,
    selected: Boolean,
    joinStart: Boolean,
    joinEnd: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DotCellSize)
            .drawBehind {
                drawJoins(joinStart, joinEnd)
                val radius = DotSize.toPx() / 2
                if (selected) {
                    drawCircle(color.copy(alpha = 0.2f), radius = radius + 3.dp.toPx())
                    drawCircle(color, radius = radius)
                } else {
                    drawCircle(OrpheusColors.darkVoid, radius = radius)
                    drawCircle(color, radius = radius - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
            .focusRing(CircleShape)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/** Dashed amber: where the sound is now, not yet a preset. Tapping it does nothing. */
@Composable
private fun NowDot(joinStart: Boolean) {
    Box(
        modifier = Modifier
            .size(DotCellSize)
            .semantics { contentDescription = NOW_DOT_DESCRIPTION }
            .drawBehind {
                drawJoins(joinStart, joinEnd = false)
                val radius = DotSize.toPx() / 2 - 1.dp.toPx()
                drawCircle(OrpheusColors.darkVoid, radius = radius)
                val dash = 3.dp.toPx()
                val dashes = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f))
                drawCircle(PresetAmber, radius = radius, style = Stroke(2.dp.toPx(), pathEffect = dashes))
            },
    )
}

/** Half-lines to the neighbouring cells, drawn under the dot so the row reads as one joined line. */
private fun DrawScope.drawJoins(joinStart: Boolean, joinEnd: Boolean) {
    val y = size.height / 2
    val color = PresetTeal.copy(alpha = 0.35f)
    val stroke = 2.dp.toPx()
    if (joinStart) drawLine(color, Offset(0f, y), Offset(size.width / 2, y), stroke)
    if (joinEnd) drawLine(color, Offset(size.width / 2, y), Offset(size.width, y), stroke)
}

/** An outlined pill in the Mix panel's style (RESET, BAND | TRACKS); [filled] marks an on state. */
@Composable
internal fun PresetPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else PresetMuted.copy(alpha = 0.5f)
    Text(
        text = text,
        color = if (filled) Color.Black else tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        modifier = modifier
            .clip(PillShape)
            .background(if (filled) tint else Color.Transparent)
            .border(1.dp, tint.copy(alpha = 0.6f), PillShape)
            .focusRing(PillShape)
            .semantics { if (filled) selected = true }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The preset bar's switch on Pulsar's transport row, in PERC's place: built like ENDING beside it, a
 * SAVES label over an eye that opens and tints while the bar shows.
 */
@Composable
internal fun PresetsToggle(feature: VibePresetsFeature) {
    val chrome by feature.collectChrome()
    val shown = chrome.barShown
    LabeledDropdown(
        label = PRESETS_TOGGLE_LABEL,
        onClick = { feature.actions.setBarShown(!shown) },
        background = if (shown) OrpheusColors.cosmicPurple.copy(alpha = 0.35f) else OrpheusColors.darkVoid.copy(alpha = 0.6f),
        // No width floor: the eye never changes width, and the row is tight on a phone. The start
        // margin keeps the pill off Pulsar's border.
        modifier = Modifier.padding(start = 8.dp).semantics(mergeDescendants = true) { selected = shown },
    ) {
        Icon(
            imageVector = if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = if (shown) HIDE_SAVES_DESCRIPTION else SHOW_SAVES_DESCRIPTION,
            tint = OrpheusColors.cosmicPurple.lighten(),
            modifier = Modifier.size(DropdownIconSize),
        )
    }
}

/**
 * Pulsar's `transportLead`: the PRESETS toggle in PERC's place where presets are available, else
 * null so the row keeps PERC (the AI edition). The Mix tab's PERC fader covers PERC here.
 */
@Composable
internal fun rememberPresetsTransportLead(feature: VibePresetsFeature): (@Composable () -> Unit)? {
    val chrome by feature.collectChrome()
    val toggle = remember(feature) { @Composable { PresetsToggle(feature) } }
    return if (chrome.available) toggle else null
}

@Preview(widthDp = 360, name = "Preset bar")
@Composable
private fun VibePresetBarPreview() {
    OrpheusTheme {
        VibePresetBar(
            feature = VibePresetsViewModel.previewFeature(
                VibePresetsUiState(
                    available = true,
                    barShown = true,
                    vibeName = "A Whale's Dream",
                    presets = listOf(
                        PresetRow(1, "Quiet Night", 0L),
                        PresetRow(2, "Deep Bass + Slow Horn", 0L),
                        PresetRow(3, "Loud Drums", 0L),
                    ),
                    selectedId = 2,
                    changed = true,
                ),
            ),
            onManage = {},
        )
    }
}

@Preview(widthDp = 360, name = "Preset bar, empty")
@Composable
private fun VibePresetBarEmptyPreview() {
    OrpheusTheme {
        VibePresetBar(
            feature = VibePresetsViewModel.previewFeature(VibePresetsUiState(available = true, barShown = true)),
            onManage = {},
        )
    }
}

/** A white ring while this control holds focus, so a D-pad user can see where they are. */
@Composable
internal fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
}

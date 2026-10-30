package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchCable
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchFamily
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/** A new cable starts at +50%. */
private const val DEFAULT_AMOUNT = 0.5f

/**
 * One duo's patch bay: outputs on the left, knob inputs on the right, cables between. Drag an
 * output onto an input to patch, drag a plug off to unpatch, tap a jack or cable to focus it.
 * Focus chips pick what is bright; with All, the amounts sit in a list under the bay. Needs a
 * bounded height: board and list scroll together when they don't fit. [roomy] gives finger-sized
 * rows for the sheet.
 */
@Composable
fun PatchBay(
    patch: DuoPatch,
    engine: OrpheusEngineId,
    onPatchChange: (DuoPatch) -> Unit,
    modifier: Modifier = Modifier,
    roomy: Boolean = false,
    state: PatchBayState = rememberPatchBayState(),
) {
    // A focused cable that is gone (unpatched, or split by the bridge) hands focus back to All.
    val focus = state.focus
    LaunchedEffect(focus, patch) {
        if (focus is PatchFocus.Cable && patch.cables.none { focus.matches(it) }) state.focus = PatchFocus.All
    }

    Column(modifier) {
        FocusChips(focus = focus, onFocus = { state.focus = it })
        Spacer(Modifier.height(6.dp))
        // A plug drag consumes its pointer before this scroll sees it, so dragging a cable never scrolls.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            val spacing = if (roomy) BaySpacing.Roomy else BaySpacing.Compact
            PatchBoard(patch, engine, onPatchChange, state, spacing, Modifier.fillMaxWidth())
            if (focus == PatchFocus.All && patch.cables.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PatchAmountList(patch = patch, engine = engine, onPatchChange = onPatchChange, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun PatchFocus.Cable.matches(cable: PatchCable) =
    cable.source == source && cable.dest == dest && cable.slot == slot

@Composable
private fun PatchBoard(
    patch: DuoPatch,
    engine: OrpheusEngineId,
    onPatchChange: (DuoPatch) -> Unit,
    state: PatchBayState,
    spacing: BaySpacing,
    modifier: Modifier,
) {
    val jacks = state.jacks
    val drawn = drawnCables(patch, engine, state, jacks)
    val latestPatch by rememberUpdatedState(patch)
    val latestDrawn by rememberUpdatedState(drawn)
    val latestOnChange by rememberUpdatedState(onPatchChange)

    Box(
        modifier
            .onGloballyPositioned { state.onBayPositioned(it) }
            .pointerInput(state, spacing) {
                patchGestures(state, spacing, { latestPatch }, { latestDrawn }, { latestOnChange(it) })
            },
    ) {
        CableCanvas(drawn, state, Modifier.matchParentSize())
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            OutputJacks(patch, state, spacing)
            Spacer(Modifier.weight(1f).widthIn(min = 64.dp))
            InputJacks(patch, engine, state, spacing, onPatchChange)
        }
        CableOverlays(drawn, state, onAmount = { cable, amount ->
            latestOnChange(latestPatch.setAmount(cable.source, cable.dest, cable.slot, amount))
        })
    }
}

/** Drag from an output to patch, drag a plug to move or unpatch it, tap a jack or cable to focus. */
private suspend fun PointerInputScope.patchGestures(
    state: PatchBayState,
    spacing: BaySpacing,
    patch: () -> DuoPatch,
    drawn: () -> List<DrawnCable>,
    onChange: (DuoPatch) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val jackRadius = spacing.hitRadius.toPx()
    val cableTolerance = 8.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        // Mouse: only the primary button patches or taps; a right click is left alone.
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val jacks = state.jacks
        val hit = nearestJack(down.position, jacks, jackRadius)
        val start = when (hit) {
            is JackId.Out -> PatchDrag(hit, null, down.position)
            is JackId.In -> plugAt(patch(), hit, state)?.let { PatchDrag(JackId.Out(it.source), it, down.position) }
            null -> null
        }
        if (start != null) down.consume()
        var moved = false
        var claimed = false
        var pointer = down.position
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            // A knob on a cable took this gesture; it is not a tap on the cable under it.
            if (start == null && change.isConsumed) claimed = true
            pointer = change.position
            if (!moved && (pointer - down.position).getDistance() > slop) moved = true
            if (start != null) {
                change.consume()
                if (moved) state.drag = start.copy(pointer = pointer)
            }
            if (!change.pressed) break
        }
        state.drag = null
        when {
            claimed -> Unit
            start != null && moved -> drop(start, pointer, patch(), state, jackRadius, onChange)
            start != null -> tap(start.plug ?: firstCableFrom(patch(), start.from.source, state), patch(), state, onChange)
            !moved -> hitCable(pointer, drawn().map { it.points }, cableTolerance)
                ?.let { tap(drawn()[it].cable, patch(), state, onChange) }
        }
    }
}

/** The plug to pull from an input: the focused cable there, else the newest one. */
private fun plugAt(patch: DuoPatch, jack: JackId.In, state: PatchBayState): PatchCable? {
    val here = patch.cables.filter { it.inJack == jack }
    return here.firstOrNull { state.isFocused(it) && state.focus is PatchFocus.Cable } ?: here.lastOrNull()
}

/** Tapping an output again steps through its cables. */
private fun firstCableFrom(patch: DuoPatch, source: PatchSource, state: PatchBayState): PatchCable? {
    val from = patch.cables.filter { it.source == source }
    val current = (state.focus as? PatchFocus.Cable)?.let { f -> from.indexOfFirst { f.matches(it) } } ?: -1
    return from.getOrNull((current + 1) % from.size.coerceAtLeast(1))
}

private fun tap(cable: PatchCable?, patch: DuoPatch, state: PatchBayState, onChange: (DuoPatch) -> Unit) {
    cable ?: return
    if (cable.parked) onChange(patch.wake(cable.source, cable.dest, cable.slot))
    state.focusOn(cable)
}

private fun drop(
    drag: PatchDrag,
    at: Offset,
    patch: DuoPatch,
    state: PatchBayState,
    radius: Float,
    onChange: (DuoPatch) -> Unit,
) {
    val inputs = state.jacks.filterKeys { it is JackId.In }
    val target = nearestJack(at, inputs, radius) as JackId.In?
    val source = drag.from.source
    val plug = drag.plug
    if (target == null) {
        if (plug != null) onChange(patch.disconnect(plug.source, plug.dest, plug.slot))
        return
    }
    val slot = patch.slotFor(target)
    if (plug != null && plug.dest == target.dest && plug.slot == slot) return
    val exists = patch.cables.any { it.source == source && it.dest == target.dest && it.slot == slot }
    val base = if (plug != null) patch.disconnect(plug.source, plug.dest, plug.slot) else patch
    val next = if (exists) base else base.connect(source, target.dest, slot, plug?.amount ?: DEFAULT_AMOUNT)
    if (next != patch) onChange(next)
}

@Composable
private fun FocusChips(focus: PatchFocus, onFocus: (PatchFocus) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusChip("All", "ALL", OrpheusColors.pickerText, focus == PatchFocus.All) { onFocus(PatchFocus.All) }
        PatchFamily.entries.forEach { family ->
            FocusChip(
                label = familyLabel(family),
                key = family.name,
                hue = PatchColors.family(family),
                on = focus == PatchFocus.Family(family),
            ) { onFocus(PatchFocus.Family(family)) }
        }
    }
}

@Composable
private fun FocusChip(label: String, key: String, hue: Color, on: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (on) OrpheusColors.pickerSurface else hue,
        fontSize = 11.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .testTag("patch-chip-$key")
            .semantics { selected = on }
            .clip(CircleShape)
            .background(if (on) hue else Color.Transparent)
            .border(1.dp, if (on) hue else OrpheusColors.pickerOutline, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

internal fun familyLabel(family: PatchFamily): String = when (family) {
    PatchFamily.LFO -> "LFO"
    PatchFamily.WAVES -> "Waves"
    PatchFamily.FLUX -> "Flux"
    PatchFamily.FM -> "FM"
}

@Preview(name = "Patch bay")
@Composable
private fun PatchBayPreview() {
    OrpheusTheme {
        val patch = DuoPatch()
            .connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.4f)
            .connect(PatchSource.FLUX_X1, PatchDest.TIMBRE, amount = -0.2f)
            .connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.15f)
        PatchBay(
            patch = patch,
            engine = OrpheusEngineId.GRN,
            onPatchChange = {},
            modifier = Modifier.background(OrpheusColors.pickerSurface).padding(10.dp),
        )
    }
}

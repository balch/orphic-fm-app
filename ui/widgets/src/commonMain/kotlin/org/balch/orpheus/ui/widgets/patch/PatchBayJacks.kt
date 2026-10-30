package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SignalPath
import org.balch.orpheus.core.audio.knobMeaning
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchFamily
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.reachesEngine
import org.balch.orpheus.ui.theme.OrpheusColors

private val JackSize = 14.dp

/** Row pitch and grab radius: compact in the popover, finger-sized (about 32dp targets) in the sheet. */
internal class BaySpacing(val outRow: Dp, val inRow: Dp, val hitRadius: Dp) {
    companion object {
        val Compact = BaySpacing(outRow = 18.dp, inRow = 24.dp, hitRadius = 14.dp)
        val Roomy = BaySpacing(outRow = 30.dp, inRow = 32.dp, hitRadius = 16.dp)
    }
}
private const val DIMMED = 0.35f

/** The knob's label on [engine], falling back to the destination's own name. */
internal fun destLabel(dest: PatchDest, engine: OrpheusEngineId): String {
    val param = when (dest) {
        PatchDest.HARMONICS -> EngineParam.HARMONICS
        PatchDest.TIMBRE -> EngineParam.TIMBRE
        PatchDest.MORPH -> EngineParam.MORPH
        else -> null
    }
    val meaning = param?.let { knobMeaning(engine, it, SignalPath.PANEL) }?.takeIf { it.active }
    return meaning?.short ?: dest.name.lowercase()
}

internal fun sourceLabel(source: PatchSource): String = when (source.family) {
    PatchFamily.FM -> "Voice FM"
    else -> "${familyLabel(source.family)} ${source.label}"
}

/** The duo knobs the bay shows: fm only on OSC, or while a cable still sits on it. */
internal fun duoDests(patch: DuoPatch, engine: OrpheusEngineId): List<PatchDest> =
    listOf(PatchDest.HARMONICS, PatchDest.TIMBRE, PatchDest.MORPH) +
        listOfNotNull(PatchDest.FM.takeIf { engine == OrpheusEngineId.OSC || patch.cables.any { it.dest == PatchDest.FM } })

/** Outputs grouped by family in one column of jacks, labels on their left, so every cable leaves into open space. */
@Composable
internal fun OutputJacks(patch: DuoPatch, state: PatchBayState, spacing: BaySpacing) {
    val focusedFamily = (state.focus as? PatchFocus.Family)?.family
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PatchFamily.entries.forEach { family ->
            val dim = focusedFamily != null && focusedFamily != family
            Column(Modifier.alpha(if (dim) 0.4f else 1f)) {
                SectionLabel(if (family == PatchFamily.FM) "VOICE FM" else familyLabel(family).uppercase(), PatchColors.family(family))
                PatchSource.entries.filter { it.family == family }.forEach { source ->
                    Row(Modifier.height(spacing.outRow), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (source == PatchSource.VOICE_FM) "partner" else source.label,
                            color = OrpheusColors.pickerText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(48.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Jack(
                            id = JackId.Out(source),
                            tag = "patch-out-${source.name}",
                            ring = PatchColors.source(source),
                            plug = if (patch.cables.any { it.source == source }) PatchColors.source(source) else null,
                            state = state,
                        )
                    }
                }
            }
        }
    }
}

/** DUO knobs, one jack each; VOICES rows with A, the bridge switch, and B. */
@Composable
internal fun InputJacks(
    patch: DuoPatch,
    engine: OrpheusEngineId,
    state: PatchBayState,
    spacing: BaySpacing,
    onPatchChange: (DuoPatch) -> Unit,
) {
    Column {
        SectionLabel("DUO", PatchColors.input)
        duoDests(patch, engine).forEach { dest ->
            Row(
                Modifier.height(spacing.inRow).alpha(if (dest.reachesEngine(engine)) 1f else DIMMED),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InJack(patch, JackId.In(dest, VoiceSlot.BOTH), state)
                Spacer(Modifier.width(8.dp))
                JackLabel(destLabel(dest, engine))
            }
        }
        Spacer(Modifier.height(6.dp))
        // A and B sit over their jacks; the section name takes the label column.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(JackSize), contentAlignment = Alignment.Center) { SectionLabel("A", PatchColors.input) }
            Spacer(Modifier.width(30.dp))
            Box(Modifier.width(JackSize), contentAlignment = Alignment.Center) { SectionLabel("B", PatchColors.input) }
            Spacer(Modifier.width(8.dp))
            SectionLabel("VOICES", PatchColors.input)
        }
        listOf(PatchDest.PITCH, PatchDest.ENV).forEach { dest ->
            val bridged = patch.isBridged(dest)
            Row(
                Modifier.height(spacing.inRow).alpha(if (dest.reachesEngine(engine)) 1f else DIMMED),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Bridged, the A jack takes the cable for both voices and is tagged BOTH.
                InJack(patch, JackId.In(dest, if (bridged) VoiceSlot.BOTH else VoiceSlot.A), state)
                Spacer(Modifier.width(4.dp))
                RotaryBridgeSwitch(
                    bridged = bridged,
                    onBridgedChange = { onPatchChange(patch.setBridged(dest, it)) },
                    modifier = Modifier.testTag("patch-bridge-${dest.name}"),
                    size = 22.dp,
                )
                Spacer(Modifier.width(4.dp))
                InJack(patch, JackId.In(dest, VoiceSlot.B), state, linked = bridged)
                Spacer(Modifier.width(8.dp))
                JackLabel(destLabel(dest, engine))
            }
        }
    }
}

@Composable
private fun InJack(patch: DuoPatch, id: JackId.In, state: PatchBayState, linked: Boolean = false) {
    val newest = patch.cables.lastOrNull { it.inJack == id }
    Jack(
        id = id,
        tag = "patch-in-${id.dest.name}-${id.slot.name}",
        ring = PatchColors.input.copy(alpha = if (linked) 0.5f else 1f),
        plug = newest?.let { PatchColors.source(it.source) },
        state = state,
    )
}

/**
 * A jack ring that reports its centre to [state]; [plug] fills it when a cable is in. Keyed by
 * [id] so a jack that changes identity (a bridged row splitting) is a new node that reports again.
 */
@Composable
private fun Jack(id: JackId, tag: String, ring: Color, plug: Color?, state: PatchBayState) = key(id) {
    DisposableEffect(id) { onDispose { state.removeJack(id) } }
    Canvas(
        Modifier
            .size(JackSize)
            .testTag(tag)
            .onGloballyPositioned { state.onJackPositioned(id, it) },
    ) {
        val r = size.minDimension / 2f
        drawCircle(OrpheusColors.pickerField, r)
        drawCircle(ring, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        if (plug != null) drawCircle(plug, r * 0.45f)
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
}

@Composable
private fun JackLabel(text: String) {
    Text(text, color = OrpheusColors.pickerText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
}

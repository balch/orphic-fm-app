package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.knobValueText

internal fun slotSwitchTag(index: Int) = "mixer-slot-$index"

internal fun voiceKnobTag(index: Int, knob: Int) = "mixer-voice-$index-$knob"

private val SWITCH_WIDTH = 16.dp
private val SWITCH_HEIGHT = 30.dp
private val LEVER_WHITE = Color(0xFFF4F1EA)
private val NUT_LIGHT = Color(0xFFD9DCE1)
private val NUT_DARK = Color(0xFF7D828B)

/**
 * The strip's EDM / SPACE switch and the two engines it chooses between: an old bat-handle toggle, lever up for EDM,
 * down for SPACE. The picked engine reads bright; a dot marks the one playing.
 */
@Composable
internal fun SlotHeader(track: MixerTrack, onPick: (EngineSlot) -> Unit) {
    val up = track.slot == EngineSlot.EDM
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .size(SWITCH_WIDTH, SWITCH_HEIGHT)
                .testTag(slotSwitchTag(track.index))
                .semantics {
                    contentDescription = "${track.name} engine slot"
                    stateDescription = if (up) "EDM" else "Space"
                }
                .toggleable(value = up, role = Role.Switch) { onPick(if (it) EngineSlot.EDM else EngineSlot.SPACE) },
        ) {
            val plate = size.width * 0.9f
            drawRoundRect(
                MixerInk.well,
                topLeft = Offset((size.width - plate) / 2f, 0f),
                size = Size(plate, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            val c = center
            val nut = size.width * 0.32f
            drawCircle(Brush.radialGradient(listOf(NUT_LIGHT, NUT_DARK), c, nut), nut, c)
            val tipY = if (up) size.height * 0.14f else size.height * 0.86f
            val tip = Offset(c.x, tipY)
            drawLine(LEVER_WHITE, c, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(LEVER_WHITE, 2.6.dp.toPx(), tip)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            SlotLine("EDM", track.edmEngine, picked = up, heard = track.slotHeard == up)
            SlotLine("SPC", track.spaceEngine, picked = !up, heard = track.slotHeard != up)
        }
    }
}

@Composable
private fun SlotLine(slot: String, engine: String, picked: Boolean, heard: Boolean) {
    Text(
        (if (heard) "• " else "  ") + "$slot $engine",
        style = mixText(
            legible(LegibleText.MIXER_MIN),
            if (picked) MixerInk.text else MixerInk.dim,
            if (picked) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Harmonics, timbre and morph of the picked engine. Amber is moved off the file, accent is pinned, dim follows its
 * macro: the first turn pins it from the value heard, so nothing jumps. Double tap returns knob and pin to the file.
 */
@Composable
internal fun VoiceKnobRow(track: MixerTrack, onSet: (Int, Int, Float?) -> Unit, onHeld: (String?) -> Unit, size: Dp) {
    Row(Modifier.fillMaxWidth()) {
        track.voice.forEachIndexed { k, v ->
            val color = when {
                v.changed -> MixerInk.changed
                v.pinned -> MixerInk.accent
                else -> MixerInk.dim
            }
            // A third of the strip each, so a label can be wider than its knob.
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                StripKnob(
                    label = null,
                    value = v.value,
                    range = 0f..1f,
                    onValueChange = { onSet(track.index, k, it) },
                    onHeld = { held -> onHeld(held?.let { voiceReadout(v, it) }) },
                    size = size,
                    modifier = Modifier.testTag(voiceKnobTag(track.index, k)),
                    onReset = if (v.active) ({ onSet(track.index, k, null) }) else null,
                    color = color,
                    enabled = v.active,
                )
                Text(
                    v.label,
                    style = mixText(legible(LegibleText.MIXER_MIN), if (v.active) color else MixerInk.line),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "detune 0.42", "patch Solid bass", with where the value comes from while it is not the knob's own. */
internal fun voiceReadout(v: MixerVoiceKnob, value: Float): String {
    val text = knobValueText(v.engineId, v.knob, value, ::f2)
    return when {
        !v.active -> v.meaning
        v.heldBySection -> "${v.label} $text · section"
        v.source != null -> "${v.label} $text · ${v.source.name}"
        else -> "${v.label} $text"
    }
}

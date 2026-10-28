package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.knobValueText

internal fun slotSwitchTag(index: Int) = "mixer-slot-$index"

internal fun voiceKnobTag(index: Int, knob: Int) = "mixer-voice-$index-$knob"

internal fun voiceButtonTag(index: Int) = "mixer-voice-button-$index"

internal const val VOICE_POPOVER_TAG = "mixer-voice-popover"
internal const val VOICE_SCRIM_TAG = "mixer-voice-scrim"

private val POPOVER_KNOB = 44.dp
private val POPOVER_WIDTH = 300.dp

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

/** Amber once a knob moved off the file, accent while one is pinned, else dim. */
private fun voiceColor(track: MixerTrack): Color = when {
    track.voice.any { it.changed } -> MixerInk.changed
    track.voice.any { it.pinned } -> MixerInk.accent
    else -> MixerInk.dim
}

/** The track's sound chip: the picked slot's engine, which fits a narrow strip. A tap opens [VoicePopover]. */
@Composable
internal fun VoiceButton(track: MixerTrack, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val color = voiceColor(track)
    val shape = RoundedCornerShape(5.dp)
    Text(
        if (track.slot == EngineSlot.EDM) track.edmEngine else track.spaceEngine,
        style = mixText(legible(LegibleText.MIXER_MIN), if (color == MixerInk.dim) MixerInk.text else color, FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .testTag(voiceButtonTag(track.index))
            .clip(shape)
            .background(MixerInk.well)
            .border(1.dp, if (color == MixerInk.dim) MixerInk.line else color, shape)
            .clickable(onClickLabel = "${track.name} sound", onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

/** The popped-out sound panel for one track: the slot switch, its engine's three knobs, a readout and the help. */
@Composable
internal fun VoicePopover(track: MixerTrack, actions: AiMixerActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val i = track.index
    var readout by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(12.dp)
    // Focus comes here on open, so the panel's Esc handler sees the keys.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier
            .testTag(VOICE_POPOVER_TAG)
            .focusRequester(focus)
            .focusable()
            .widthIn(max = POPOVER_WIDTH * textScale())
            .clip(shape)
            .background(MixerInk.card)
            .border(1.dp, MixerInk.accent, shape)
            // Taps inside stay inside; the scrim behind closes it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.tracks.getOrElse(i) { MixerInk.accent }, FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MixerButton("Done", onClose)
        }
        SlotHeader(track) { actions.pickSlot(i, it) }
        VoiceKnobRow(
            track,
            onSet = { t, k, v -> actions.setVoiceKnob(t, EngineKnob.entries[k], v) },
            onHeld = { readout = it },
            size = POPOVER_KNOB,
        )
        // Holds its line while empty, so the help under it never jumps.
        Text(
            readout ?: " ",
            style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text, FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VoiceNote()
    }
}

@Composable
internal fun VoiceNote() {
    Text(
        "Dim knobs follow MOOD or SPACE, and turning one pins it from what you hear. Double-tap returns it to the " +
            "file. The switch picks the EDM or SPACE engine; • marks the one playing.",
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
    )
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

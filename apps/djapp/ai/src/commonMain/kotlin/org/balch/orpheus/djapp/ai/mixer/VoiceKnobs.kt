package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.FmPatchNames
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.knobValueText
import org.balch.orpheus.features.pulsar.models.SlotLock
import org.balch.orpheus.features.pulsar.pulsarEngineLabel
import org.balch.orpheus.ui.widgets.engine.EnginePicker
import org.balch.orpheus.ui.widgets.engine.EnginePickerController
import org.balch.orpheus.ui.widgets.engine.enginePickerTestTag
import org.balch.orpheus.ui.widgets.engine.rememberEnginePickerController

internal fun voiceKnobTag(index: Int, knob: Int) = "mixer-voice-$index-$knob"

internal fun voiceButtonTag(index: Int) = "mixer-voice-button-$index"

internal fun slotLockTag(index: Int, lock: SlotLock) = "mixer-lock-$index-${lock.name.lowercase()}"

internal fun enginePickTag(index: Int, slot: EngineSlot) = "engine-pick-$index-${if (slot == EngineSlot.EDM) "edm" else "space"}"

internal const val VOICE_POPOVER_TAG = "mixer-voice-popover"
internal const val VOICE_SCRIM_TAG = "mixer-voice-scrim"
internal const val VOICE_MIX_TAG = "mixer-voice-mix"

/** Beside the engine that plays. */
internal const val PLAYING_DOT = "• playing"

internal const val LOCK_HINT = "Lock EDM or SPACE to tune its knobs"

private val POPOVER_KNOB = 44.dp
private val POPOVER_WIDTH = 340.dp

/** Knobs under AUTO: plainly off, fainter than a dim knob that follows its macro. */
private const val OFF_ALPHA = 0.35f

/** The engine row the lock does not hold. */
private const val UNLOCKED_ROW_ALPHA = 0.5f

private val PILL_PAD = 3.dp

/** Added above the knob row, on top of the column's own spacing. */
private val KNOB_TOP_GAP = 8.dp

/** Section heads in the pop-out: PLAYS and MIX. */
@Composable
private fun PopoverLabel(text: String) {
    Text(text, style = mixText(legible(LegibleText.SECONDARY), MixerInk.dim, FontWeight.SemiBold), maxLines = 1)
}

/** Auto / EDM / SPACE as one segmented control; the held engine carries a lock. */
@Composable
private fun LockControl(track: MixerTrack, onLock: (SlotLock) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val textSize = legible(LegibleText.SECONDARY)
    val iconSize = with(LocalDensity.current) { textSize.toDp() }
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .clip(shape)
            .border(1.dp, MixerInk.line, shape),
    ) {
        SlotLock.entries.forEach { lock ->
            val on = track.slotLock == lock
            Row(
                Modifier
                    .weight(1f)
                    .testTag(slotLockTag(track.index, lock))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onLock(lock) })
                    .background(if (on) MixerInk.accent else Color.Transparent)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on && lock != SlotLock.AUTO) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MixerInk.text, modifier = Modifier.size(iconSize))
                }
                Text(
                    if (lock == SlotLock.AUTO) "Auto" else lock.name,
                    style = mixText(textSize, if (on) MixerInk.text else MixerInk.dim, if (on) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One slot: its name, its engine picker and a dot while it plays. The held row is lit; the other dims under a lock, but
 * its picker still works. The pill stands at the text's height. A DX patch pick passes its harmonics with the engine.
 */
@Composable
private fun EngineRow(
    track: MixerTrack,
    slot: EngineSlot,
    controller: EnginePickerController,
    onEngine: (EngineSlot, OrpheusEngineId, Float?) -> Unit,
) {
    val edm = slot == EngineSlot.EDM
    val engine = if (edm) track.edmEngineId else track.spaceEngineId
    val harmonics = if (edm) track.edmHarmonics else track.spaceHarmonics
    val held = track.knobsLive && track.slot == slot
    val playing = track.slot == slot
    val textSize = legible(LegibleText.SECONDARY)
    val labelStyle = mixText(textSize, MixerInk.text, FontWeight.Bold)
    // Text merges its style into the theme's, so measuring has to as well.
    val base = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measured = measurer.measure("Ag", base.merge(labelStyle), maxLines = 1).size
    val pillHeight = with(density) { measured.height.toDp() }
    val choices = remember { EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true) }
    val patchIndex = EngineCatalog.patchAt(engine, harmonics)?.index
    val current = remember(engine, patchIndex) { EngineCatalog.choiceFor(engine, harmonics) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (track.knobsLive && !held) UNLOCKED_ROW_ALPHA else 1f)
            .clip(shape)
            .background(if (held) MixerInk.well else Color.Transparent)
            .border(1.dp, if (held) MixerInk.accent else Color.Transparent, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (edm) "EDM" else "SPACE",
            style = mixText(textSize, MixerInk.text, if (held) FontWeight.Bold else FontWeight.Normal),
            maxLines = 1,
            modifier = Modifier.width(56.dp * textScale()),
        )
        EnginePicker(
            current = current,
            choices = choices,
            onPick = { choice ->
                val harmonic = (choice as? EngineChoice.Patch)?.patch?.harmonics
                onEngine(slot, choice.engine, harmonic)
            },
            color = MixerInk.text,
            compact = true,
            pillHeight = pillHeight,
            labelStyle = labelStyle,
            controller = controller,
            pillLabel = (current as? EngineChoice.Engine)?.let { pulsarEngineLabel(it.engine.id) },
            modifier = Modifier.enginePickerTestTag(enginePickTag(track.index, slot)),
        )
        if (playing) Text(PLAYING_DOT, style = mixText(textSize, MixerInk.green), maxLines = 1)
    }
}

/** The harmonics knob on a DX engine: the patch sounding now under it, and the picked one when a walk has moved off it. */
internal data class DxKnobLabel(val short: String, val long: String)

/** Null off DX. [activePatch] is the engine's sounding patch (-1 when it reports none); [harmonics] is the stored pick. */
internal fun dxKnobLabel(engine: OrpheusEngineId, harmonics: Float, activePatch: Int): DxKnobLabel? {
    val picked = EngineCatalog.patchAt(engine, harmonics) ?: return null
    val sounding = if (activePatch >= 0) FmPatchNames.patchNameAt(engine, activePatch) ?: picked.name else picked.name
    val long = if (sounding == picked.name) "${engine.name} · $sounding" else "${engine.name} · $sounding (picked ${picked.name})"
    return DxKnobLabel(sounding, long)
}

/**
 * Harmonics, timbre and morph of the track's slot. Amber is moved off the file, accent is pinned, dim follows its
 * macro: the first turn pins it from the value heard, so nothing jumps. Double tap returns knob and pin to the file.
 * Under AUTO they show what plays but are off: faded and disabled.
 */
@Composable
internal fun VoiceKnobRow(
    track: MixerTrack,
    onSet: (Int, Int, Float?) -> Unit,
    onHeld: (String?) -> Unit,
    size: Dp,
    onOpenPatches: () -> Unit = {},
) {
    val live = track.knobsLive
    Row(Modifier.fillMaxWidth()) {
        track.voice.forEachIndexed { k, v ->
            // A DX patch selector is picked, not turned: it locks, names the sounding patch, and opens the picker.
            val dx = if (v.knob == EngineKnob.HARMONICS) dxKnobLabel(v.engineId, v.value, track.activePatch) else null
            val color = when {
                v.changed -> MixerInk.changed
                v.pinned -> MixerInk.accent
                else -> MixerInk.dim
            }
            val turns = live && v.active
            // A third of the strip each, so a label can be wider than its knob.
            Column(
                Modifier.weight(1f).alpha(if (live) 1f else OFF_ALPHA),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StripKnob(
                    label = null,
                    value = v.value,
                    range = 0f..1f,
                    onValueChange = { onSet(track.index, k, it) },
                    onHeld = { held -> onHeld(held?.let { dx?.long ?: voiceReadout(v, it) }) },
                    size = size,
                    modifier = Modifier
                        .testTag(voiceKnobTag(track.index, k))
                        .then(if (live) Modifier else Modifier.semantics { disabled() }),
                    onReset = if (turns && dx == null) ({ onSet(track.index, k, null) }) else null,
                    color = color,
                    enabled = turns,
                    locked = dx != null,
                    onLockedClick = onOpenPatches,
                )
                Text(
                    dx?.short ?: v.label,
                    style = mixText(legible(LegibleText.SECONDARY), if (v.active) color else MixerInk.line),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Amber once a knob on either slot moved off the file, accent while one is pinned, else dim. */
private fun voiceColor(track: MixerTrack): Color = when {
    track.voiceChanged -> MixerInk.changed
    track.voice.any { it.pinned } -> MixerInk.accent
    else -> MixerInk.dim
}

/** The track's sound chip: the engine of its slot, which fits a narrow strip. A tap opens [VoicePopover]. */
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

/**
 * The popped-out panel for one track, one column: the lock, both engines, the knobs, then the track's mix. It scrolls
 * when the window is too short to hold it.
 */
@Composable
internal fun VoicePopover(
    track: MixerTrack,
    pinned: Boolean,
    actions: AiMixerActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                style = mixText(legible(LegibleText.TITLE), MixerInk.tracks.getOrElse(i) { MixerInk.accent }, FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MixerButton("Done", onClose)
        }
        PopoverLabel("PLAYS")
        LockControl(track) { actions.setSlotLock(i, it) }
        val pickers = EngineSlot.entries.associateWith { rememberEnginePickerController() }
        Column {
            for (slot in EngineSlot.entries) {
                EngineRow(track, slot, pickers.getValue(slot)) { sl, e, h -> actions.setEngine(i, sl, e, h) }
            }
        }
        // Extra room above, so the knobs do not crowd the engine rows.
        Box(Modifier.padding(top = KNOB_TOP_GAP)) {
            VoiceKnobRow(
                track,
                onSet = { t, k, v -> actions.setVoiceKnob(t, EngineKnob.entries[k], v) },
                onHeld = { readout = it },
                size = POPOVER_KNOB,
                onOpenPatches = { pickers.getValue(track.slot).open(EngineCategory.DX_PATCHES) },
            )
        }
        // Holds its line while empty, so nothing under it jumps.
        Text(
            if (track.knobsLive) readout ?: " " else LOCK_HINT,
            style = if (track.knobsLive) {
                mixText(legible(LegibleText.CHAT_BODY), MixerInk.text, FontWeight.Bold)
            } else {
                mixText(legible(LegibleText.CHAT_BODY), MixerInk.dim)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VoiceNote()
        PopoverLabel("MIX")
        VoiceMix(track, pinned, actions)
    }
}

/** The strip's own controls, laid flat: level, pan and both sends, then M, S and the pin. */
@Composable
private fun VoiceMix(track: MixerTrack, pinned: Boolean, actions: AiMixerActions) {
    val i = track.index
    val scale = textScale()
    val labelWidth = 52.dp * scale
    val valueWidth = 56.dp * scale
    val rowHeight = 28.dp
    Column(Modifier.fillMaxWidth().testTag(VOICE_MIX_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DbFaderRow(
            db = track.levelDb,
            onDbChange = { actions.setLevelDb(i, it) },
            onReset = { actions.setLevelDb(i, 0f) },
            index = i,
            modifier = Modifier.fillMaxWidth(),
            labelWidth = labelWidth,
            valueWidth = valueWidth,
            labelSize = LegibleText.SECONDARY,
            valueSize = LegibleText.CHAT_BODY,
        )
        val m = Modifier.fillMaxWidth()
        MixerSlider(
            "pan", track.pan, -1f..1f, { actions.setPan(i, it) }, ::panText, i, m,
            onReset = { actions.setPan(i, track.filePan) },
            labelWidth = labelWidth, valueWidth = valueWidth, height = rowHeight, labelSize = LegibleText.SECONDARY, valueSize = LegibleText.CHAT_BODY,
        )
        MixerSlider(
            "reverb", track.reverb, 0f..1f, { actions.setReverb(i, it) }, ::f2, i, m,
            labelWidth = labelWidth, valueWidth = valueWidth, height = rowHeight, labelSize = LegibleText.SECONDARY, valueSize = LegibleText.CHAT_BODY,
        )
        MixerSlider(
            "delay", track.delay, 0f..1f, { actions.setDelay(i, it) }, ::f2, i, m,
            labelWidth = labelWidth, valueWidth = valueWidth, height = rowHeight, labelSize = LegibleText.SECONDARY, valueSize = LegibleText.CHAT_BODY,
        )
        Row(
            Modifier.padding(top = 6.dp).height(PIN_TARGET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MuteSoloButtons(track.muted, track.soloed, { actions.toggleMute(i) }, { actions.toggleSolo(i) }, textSize = LegibleText.SECONDARY)
            Box(Modifier.padding(start = 4.dp)) { PinToggle(i, track.name, pinned, { actions.togglePin(i) }) }
        }
    }
}

@Composable
internal fun VoiceNote() {
    Text(
        "Dim knobs follow MOOD or SPACE; double-tap returns one to the file.",
        style = mixText(legible(LegibleText.SECONDARY), MixerInk.dim),
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

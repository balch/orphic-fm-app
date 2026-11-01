package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.copyPlainText
import org.balch.orpheus.djapp.ai.mixer.MixerButton
import org.balch.orpheus.djapp.ai.mixer.MixerInk
import org.balch.orpheus.djapp.ai.mixer.MixerWave
import org.balch.orpheus.djapp.ai.mixer.NO_WAVE
import org.balch.orpheus.djapp.ai.mixer.mixText
import org.balch.orpheus.djapp.ai.mixer.waveOf
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The constellation's window: two bars of the Lick Lab clock. */
private const val WINDOW_BEATS = 8.0
private const val BEATS_PER_BAR = 4

/** The constellation and takes never ask the strips area for more than this; the mixer's column scrolls past it. */
private val BODY_MIN_HEIGHT = 160.dp
private val KEY_STRIP_HEIGHT = 64.dp
private val TAKES_WIDTH = 270.dp

private val GRID_LABEL_WIDTH = 40.dp
private val GRID_LAST_WIDTH = 84.dp
private val GRID_GAP = 6.dp
private val WAVE_HEIGHT = 28.dp

/** Constellation stars and their note names, sized to read at a glance while playing. */
private val STAR_RADIUS = 5.5.dp
private const val NOTE_TEXT_SCALE = 1.35f

/** A track chip's detail line rests this long before and between scrolls. */
private const val MARQUEE_HOLD_MS = 1500

private val BOOST_INK = OrpheusColors.evoGold
private val CALL_INK = OrpheusColors.galaxyStarBlue
private val ANSWER_INK = OrpheusColors.mixerFxAmber
private val ROOT_INK = OrpheusColors.neonMagenta

internal fun takeTag(id: Long, what: String) = "lick-take-$id-$what"

internal fun playChipTag(track: Int) = "lick-lab-play-$track"

internal fun callChipTag(track: Int) = "lick-lab-call-$track"

internal fun waveCellTag(track: Int) = "lick-lab-wave-$track"

internal fun hintModeTag(mode: HintMode) = "lick-lab-hint-${mode.name.lowercase()}"

internal const val BOOST_TAG = "lick-lab-boost"

internal const val FOLLOWS_CHORDS_MARK = "follows chords: paste may transpose"

/**
 * Lick Lab in the mixer's strips area: track, caller and slot pickers, the call (top) and answer (bottom)
 * constellation, the takes and the key strip. The surface holds keyboard focus while shown; [animate] scrolls the
 * constellation every frame (off in tests, which would never go idle). [meters] feeds the track waves.
 */
@Composable
fun LickLabPanel(
    feature: LickLabFeature,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    meters: State<MeterSnapshot>? = null,
) {
    val live = feature.stateFlow.collectAsState()
    // Everything but the notes, which change every viz frame and are read only while drawing.
    val ui by remember(live) { derivedStateOf { live.value.copy(answerNotes = emptyList(), callNotes = emptyList()) } }
    val actions = feature.actions
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(requester) { requester.requestFocus() }
    // Leaving composition (the window narrowed to compact) may send no focus event, so nothing is left held.
    DisposableEffect(actions) { onDispose { actions.releaseAll() } }

    val clipboard = LocalClipboard.current
    var copies by remember { mutableIntStateOf(0) }
    var copiedNote by remember { mutableStateOf(false) }
    LaunchedEffect(feature) {
        feature.copied.collect {
            clipboard.copyPlainText(it)
            copies++
        }
    }
    // Keyed on the count, so a second copy restarts the note's two seconds.
    LaunchedEffect(copies) {
        if (copies > 0) {
            copiedNote = true
            delay(2_000)
            copiedNote = false
        }
    }
    val beatNow = remember(feature) { { feature.currentBeat() } }
    val msPerBeat = remember(feature) { { feature.msPerBeat() } }

    // Scrolls only while there is something to scroll: an armed track, a caller or notes still in the window.
    val frame = remember { mutableLongStateOf(0L) }
    val moving by remember(live) {
        derivedStateOf { live.value.run { armedTrack != null || callerTrack != null || answerNotes.isNotEmpty() } }
    }
    if (animate && moving) LaunchedEffect(Unit) { while (true) withFrameMillis { frame.longValue = it } }

    Column(
        modifier
            .testTag(LICK_LAB_TAG)
            .clip(RoundedCornerShape(8.dp))
            .background(MixerInk.card)
            // Any press on the surface brings the keyboard back to it.
            .pointerInput(requester) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    requester.requestFocus()
                }
            }
            .lickLabKeys(requester, ui.keyMode, actions) { focused = it }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LickHeader(ui, actions, meters, animate)
        Row(Modifier.weight(1f).fillMaxWidth().minIntrinsicHeight(BODY_MIN_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Constellation(live, beatNow, frame, Modifier.weight(1f).fillMaxHeight())
            Takes(ui, feature, copiedNote, Modifier.width(TAKES_WIDTH).fillMaxHeight())
        }
        val keys = remember(ui.keyMode, ui.baseMidi, ui.scale) { stripKeys(ui.keyMode, ui.baseMidi, ui.scale) }
        val chordRoot = ui.chordRootDegree
        val n = ui.scale.size
        val hint = keyHint(ui, keys)
        KeyStrip(
            keys,
            ui.heldMidi,
            isChordTone = { k -> chordRoot >= 0 && k.degree != null && (k.degree - chordRoot).mod(n) in CHORD_STEPS },
            focused = focused,
            actions = actions,
            onPress = { requester.requestFocus() },
            modifier = Modifier.fillMaxWidth().height(KEY_STRIP_HEIGHT),
            hint = hint,
            beatNow = beatNow,
            msPerBeat = msPerBeat,
            // Null once the frame clock stops, so a flash cannot freeze on screen.
            frame = if (animate && moving) frame else null,
        )
        if (!focused) {
            Text(
                "Click here to play from the keyboard.",
                style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
                maxLines = 1,
            )
        }
    }
}

/** One grid row: a label, a column per track and the last column, so every row lines up. */
@Composable
private fun GridRow(
    label: String,
    tracks: List<TrackChip>,
    last: @Composable () -> Unit,
    cell: @Composable (TrackChip, Modifier) -> Unit,
) {
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        Box(Modifier.width(GRID_LABEL_WIDTH)) { if (label.isNotEmpty()) Label(label) }
        for (t in tracks) cell(t, Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(GRID_LAST_WIDTH), contentAlignment = Alignment.Center) { last() }
    }
}

@Composable
private fun LickHeader(ui: LickLabUiState, actions: LickLabActions, meters: State<MeterSnapshot>?, animate: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridRow("PLAY", ui.tracks, last = { BoostButton(ui.boost, enabled = ui.armedTrack != null, actions::toggleBoost) }) { t, m ->
            TrackChipView(t, selected = t.index == ui.armedTrack, selectedFill = MixerInk.accent, m.testTag(playChipTag(t.index)), animate = animate) {
                actions.arm(t.index)
            }
        }
        GridRow("", ui.tracks, last = {}) { t, m ->
            TrackWave(t.index, lit = t.index == ui.armedTrack, meters, m.height(WAVE_HEIGHT).testTag(waveCellTag(t.index)))
        }
        GridRow("CALL", ui.tracks, last = { ChoicePill("none", ui.callerTrack == null) { actions.setCaller(null) } }) { t, m ->
            TrackChipView(
                t,
                selected = t.index == ui.callerTrack,
                selectedFill = CALL_INK.copy(alpha = 0.35f),
                m.testTag(callChipTag(t.index)),
                // A track cannot both play and call.
                enabled = t.index != ui.armedTrack,
                animate = animate,
            ) {
                actions.setCaller(if (t.index == ui.callerTrack) null else t.index)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("HINT")
            HintSwitch(ui.hintMode, actions::setHintMode)
            Spacer(Modifier.weight(1f))
            RecordButton(ui.recording, actions::toggleRecording)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.keyLabel, style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text, FontWeight.SemiBold), maxLines = 1)
            if (ui.krakenTarget >= 0) {
                Text("shifted", style = mixText(legible(LegibleText.MIXER_MIN), OrpheusColors.krakenTeal), maxLines = 1)
            }
            ChoicePill("in key", ui.keyMode == KeyMode.InKey) { actions.setKeyMode(KeyMode.InKey) }
            ChoicePill("chromatic", ui.keyMode == KeyMode.Chromatic) { actions.setKeyMode(KeyMode.Chromatic) }
            Label("TO")
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((slot, name) in slotChoices(ui)) {
                    ChoicePill(name, ui.slot == slot) { actions.setSlot(slot) }
                }
            }
        }
    }
}

/** The export targets: lick, bassLine, each pool entry and the armed track's solo lick when it has one. */
internal fun slotChoices(ui: LickLabUiState): List<Pair<SlotRef, String>> = buildList {
    add(SlotRef(LickSlot.Lead) to "lick")
    add(SlotRef(LickSlot.Bass) to "bassLine")
    for (i in 0 until ui.poolSize) add(SlotRef(LickSlot.Pool, i) to "pool $i")
    ui.armedTrack?.takeIf { it in ui.soloTracks }?.let { add(SlotRef(LickSlot.Solo, it) to "solo") }
}

@Composable
private fun Label(text: String) {
    Text(text, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.SemiBold), maxLines = 1)
}

@Composable
private fun TrackChipView(
    track: TrackChip,
    selected: Boolean,
    selectedFill: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    animate: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val color = MixerInk.tracks.getOrElse(track.index) { MixerInk.accent }
    Column(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(shape)
            .background(if (selected) selectedFill else MixerInk.chip)
            .border(1.dp, if (selected) color else MixerInk.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(track.name, style = mixText(legible(LegibleText.MIXER_MIN), color, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Holds, then scrolls a detail too long for the chip, and holds again at the start. Tests turn it
        // off with [animate], since an endless marquee never lets them go idle.
        Text(
            track.engine,
            style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
            maxLines = 1,
            overflow = if (animate) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (!animate) Modifier else Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = MARQUEE_HOLD_MS,
                initialDelayMillis = MARQUEE_HOLD_MS,
                velocity = 30.dp,
            ),
        )
    }
}

/** The mixer strip's own live wave for [track], lit while it is the armed track. */
@Composable
private fun TrackWave(track: Int, lit: Boolean, meters: State<MeterSnapshot>?, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val color = MixerInk.tracks.getOrElse(track) { MixerInk.accent }
    Box(
        modifier
            .clip(shape)
            .background(if (lit) MixerInk.accent.copy(alpha = 0.35f) else Color.Transparent)
            .border(1.dp, if (lit) MixerInk.accentSoft else Color.Transparent, shape)
            .padding(2.dp),
    ) {
        MixerWave({ meters?.waveOf(track) ?: NO_WAVE }, color, Modifier.fillMaxSize())
    }
}

/** +4 dB on the armed track while on; gold like the mockup, live only. */
@Composable
private fun BoostButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .testTag(BOOST_TAG)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(shape)
            .background(if (on) BOOST_INK else Color.Transparent)
            .border(1.dp, BOOST_INK, shape)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "BOOST on" else "BOOST",
            style = mixText(legible(LegibleText.MIXER_MIN), if (on) MixerInk.onBright else BOOST_INK, FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** ROOT · NEAR · MOVE: what the next-chord hint points the hand at. */
@Composable
private fun HintSwitch(mode: HintMode, onMode: (HintMode) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.clip(shape).border(1.dp, MixerInk.line, shape)) {
        for (m in HintMode.entries) {
            val on = m == mode
            Box(
                Modifier
                    .testTag(hintModeTag(m))
                    .background(if (on) HINT_INK else MixerInk.chip)
                    .clickable(role = Role.RadioButton, onClick = { onMode(m) })
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    m.name.uppercase(),
                    style = mixText(legible(LegibleText.MIXER_MIN), if (on) MixerInk.onBright else MixerInk.text, if (on) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) MixerInk.accent else MixerInk.chip)
            .border(1.dp, MixerInk.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.text), maxLines = 1)
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .testTag(RECORD_TAG)
            .clip(shape)
            .background(if (recording) MixerInk.red.copy(alpha = 0.25f) else MixerInk.chip)
            .border(1.dp, if (recording) MixerInk.red else MixerInk.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (recording) MixerInk.red else MixerInk.chip)
                .border(1.5.dp, MixerInk.red, CircleShape),
        )
        Text(if (recording) "Stop" else "Record", style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text), maxLines = 1)
    }
}

internal const val RECORD_TAG = "lick-lab-record"

/** A lick step has no tie, so held-key notes come back as fresh strikes; the export marks them `// hammered`. */
internal const val PASTE_REATTACKS_NOTE = "A pasted lick re-attacks each note, even ones played under a held key."

/** The takes, newest first, each with play, star, copy and delete, and every mark its export carries. */
@Composable
private fun Takes(ui: LickLabUiState, feature: LickLabFeature, copied: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.clip(shape).background(MixerInk.sourceCard).border(1.dp, MixerInk.line, shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TAKES", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (copied) Text("Copied", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.green), maxLines = 1)
        }
        if (ui.takes.isEmpty()) {
            Text(
                if (ui.armedTrack == null) "Pick a track to play, then Record." else "Record, then play. A bar of silence ends a take.",
                style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
            )
            Text(PASTE_REATTACKS_NOTE, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (take in ui.takes.asReversed()) {
                TakeRow(take, feature, canPlay = ui.armedTrack != null)
            }
        }
    }
}

@Composable
private fun TakeRow(take: Take, feature: LickLabFeature, canPlay: Boolean) {
    val export = remember(take) { feature.exportFor(take.id) }
    val actions = feature.actions
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MixerInk.card).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val bars = export?.let { it.loopLength / take.beatsPerBar }
            Text(
                "Take ${take.id}" + (bars?.let { " · $it bar" + if (it == 1) "" else "s" } ?: "") + " · ${take.notes.size} notes",
                style = mixText(legible(LegibleText.MIXER_MIN), if (take.starred) MixerInk.changed else MixerInk.text, FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        for (mark in takeMarks(export, take.context?.followsChords == true)) {
            Text(mark, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.changed))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TakeButton("▶", "Play take ${take.id}", takeTag(take.id, "play"), enabled = canPlay) { actions.playTake(take.id) }
            TakeButton(if (take.starred) "★" else "☆", "Star take ${take.id}", takeTag(take.id, "star")) { actions.star(take.id) }
            TakeButton("Copy", "Copy take ${take.id} as Kotlin", takeTag(take.id, "copy")) { actions.copy(take.id) }
            TakeButton("✕", "Delete take ${take.id}", takeTag(take.id, "delete")) { actions.delete(take.id) }
        }
    }
}

/**
 * What the export changed or cannot hold, so nothing is hidden: over the cap, merged, chromatic and clamped notes,
 * and a take played on a track that follows chords.
 */
internal fun takeMarks(export: ExportedLick?, followsChords: Boolean = false): List<String> {
    if (export == null) return emptyList()
    return buildList {
        if (followsChords) add(FOLLOWS_CHORDS_MARK)
        if (export.overCap) add("${export.steps.size} steps, over ${Lick.MAX_LICK_STEPS}: split before pasting")
        if (export.mergedCount > 0) add("${export.mergedCount} merged: two notes on one 16th")
        if (export.chromaticNotes.isNotEmpty()) {
            val played = export.chromaticMidi.joinToString(", ") { LickPitch.noteName(it) }
            add("chromatic $played: snapped to the scale")
        }
        if (export.clamped.isNotEmpty()) add("${export.clamped.size} clamped to degrees -32..63")
    }
}

@Composable
private fun TakeButton(text: String, description: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    MixerButton(text, onClick, Modifier.testTag(tag).semantics { contentDescription = description }, enabled = enabled)
}

/**
 * The call above the line, the answer below, on a two-bar window that scrolls with the clock. Chord tones glow, the
 * newest answer flashes when it lands on the chord's root (the tonic with no chord), and dashed lines join an answer
 * to a call on the same beat position in the bar, up to a bar later.
 */
@Composable
private fun Constellation(live: State<LickLabUiState>, beatNow: () -> Double, frame: State<Long>, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val size = legible(LegibleText.MIXER_MIN)
    val small = remember(size) { mixText(size * 0.9f, MixerInk.dim) }
    val labelStyle = remember(size) { mixText(size, MixerInk.dim, FontWeight.SemiBold) }
    val noteStyle = remember(size) { mixText(size * NOTE_TEXT_SCALE, MixerInk.text, FontWeight.Bold) }
    val density = LocalDensity.current
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) } }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(MixerInk.well)) {
        val ms = frame.value
        val s = live.value
        drawConstellation(s, beatNow(), ms, measurer, small, labelStyle, noteStyle, dash)
    }
}

private class Lane(val top: Float, val bottom: Float, val lo: Int, val hi: Int) {
    fun y(midi: Int): Float = bottom - (midi - lo).toFloat() / (hi - lo) * (bottom - top)
}

/** The pitch span of [notes] (and [extra]), at least an octave, padded a step either side. */
private fun lane(notes: List<LiveNote>, extra: Int?, center: Int, top: Float, bottom: Float): Lane {
    var lo = extra ?: Int.MAX_VALUE
    var hi = extra ?: Int.MIN_VALUE
    for (note in notes) {
        if (note.midi < lo) lo = note.midi
        if (note.midi > hi) hi = note.midi
    }
    if (lo > hi) {
        lo = center - 6
        hi = center + 6
    }
    lo -= 2
    hi += 2
    if (hi - lo < 14) {
        val mid = (lo + hi) / 2
        lo = mid - 7
        hi = mid + 7
    }
    return Lane(top, bottom, lo, hi)
}

private fun DrawScope.drawConstellation(
    s: LickLabUiState,
    now: Double,
    ms: Long,
    measurer: TextMeasurer,
    small: TextStyle,
    labelStyle: TextStyle,
    noteStyle: TextStyle,
    dash: PathEffect,
) {
    val w = size.width
    val h = size.height
    val pad = 10.dp.toPx()
    val labelRoom = 16.dp.toPx()
    val mid = h / 2
    val from = now - WINDOW_BEATS
    val v = View(from, pad, w - 2 * pad, s.chordRootDegree, s.scale.size)
    fun x(beat: Double): Float = v.x(beat)

    // Beat grid, bar lines brighter.
    for (b in ceil(from).toInt()..floor(now).toInt()) {
        val bar = b.mod(BEATS_PER_BAR) == 0
        drawLine(
            if (bar) MixerInk.line else MixerInk.chip,
            Offset(x(b.toDouble()), labelRoom),
            Offset(x(b.toDouble()), h - labelRoom),
            strokeWidth = if (bar) 1.5f else 1f,
        )
    }
    drawLine(MixerInk.line, Offset(0f, mid), Offset(w, mid))

    val n = s.scale.size
    val root = s.chordRootDegree
    // Each lane's top leaves a note name's height, so the highest star's label stays inside it.
    val noteRoom = measurer.measure("G#", noteStyle).size.height + STAR_RADIUS.toPx() * 2.4f
    val call = lane(s.callNotes, null, s.baseMidi, labelRoom + noteRoom, mid - 8.dp.toPx())
    val answer = lane(s.answerNotes, s.heldMidi, s.baseMidi, mid + noteRoom, h - labelRoom - 4f)

    drawText(measurer, "CALL", Offset(pad, 2f), labelStyle.copy(color = CALL_INK))
    drawText(measurer, "YOUR ANSWER", Offset(pad, h - labelRoom), labelStyle.copy(color = ANSWER_INK))
    if (root >= 0 && n > 0) {
        val chordMidi = LickPitch.degreeToMidi(root, s.baseMidi, s.scale)
        val text = "chord ${LickPitch.pitchName(chordMidi)} (degree $root)"
        val layout = measurer.measure(text, labelStyle)
        drawText(layout, topLeft = Offset(w - pad - layout.size.width, mid - layout.size.height - 2f))
    }
    if (s.callerTrack == null) {
        val layout = measurer.measure("Pick a caller to see its notes here.", small)
        drawText(layout, topLeft = Offset((w - layout.size.width) / 2, (labelRoom + mid - layout.size.height) / 2))
    }

    // Echo lines first, under the stars.
    for (a in s.answerNotes) {
        // An echo answers within a bar: the same beat, or the same place one bar on.
        val c = s.callNotes.lastOrNull {
            a.startBeat - it.startBeat in -0.01..BEATS_PER_BAR + 0.01 && samePlaceInBar(it.startBeat, a.startBeat)
        }
            ?: continue
        drawLine(
            ANSWER_INK.copy(alpha = 0.4f),
            Offset(x(c.startBeat), call.y(c.midi)),
            Offset(x(a.startBeat), answer.y(a.midi)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
    }

    drawLane(s.callNotes, call, now, v, CALL_INK, flash = null, ms, measurer, noteStyle)
    val newest = s.answerNotes.lastOrNull()?.takeIf { v.isRoot(it) }
    drawLane(s.answerNotes, answer, now, v, ANSWER_INK, flash = newest, ms, measurer, noteStyle)
}

/** One frame's beat-to-x mapping and chord: [root] is the chord's degree (-1 none) in a scale of [n] steps. */
private class View(val from: Double, val pad: Float, val span: Float, val root: Int, val n: Int) {
    fun x(beat: Double): Float = pad + ((beat - from) / WINDOW_BEATS).toFloat() * span

    fun chordTone(note: LiveNote): Boolean =
        root >= 0 && note.degree != null && (note.degree - root).mod(n) in CHORD_STEPS

    /** On the chord's root, or the tonic when there is no chord. */
    fun isRoot(note: LiveNote): Boolean = note.degree != null && (note.degree - root.coerceAtLeast(0)).mod(n) == 0
}

private fun samePlaceInBar(a: Double, b: Double): Boolean {
    fun place(beat: Double) = (beat * 4).roundToInt().mod(BEATS_PER_BAR * 4)
    return place(a) == place(b)
}

private fun DrawScope.drawLane(
    notes: List<LiveNote>,
    lane: Lane,
    now: Double,
    v: View,
    ink: Color,
    flash: LiveNote?,
    ms: Long,
    measurer: TextMeasurer,
    noteStyle: TextStyle,
) {
    val r = STAR_RADIUS.toPx()
    // The constellation: each star joined to the next, each note's tail as long as it sounded.
    for (i in 1 until notes.size) {
        val a = notes[i - 1]
        val b = notes[i]
        drawLine(ink.copy(alpha = 0.3f), Offset(v.x(a.startBeat), lane.y(a.midi)), Offset(v.x(b.startBeat), lane.y(b.midi)))
    }
    for (note in notes) {
        val cx = v.x(note.startBeat)
        val cy = lane.y(note.midi)
        val sounding = note.endBeat == null
        drawLine(ink.copy(alpha = if (sounding) 0.8f else 0.4f), Offset(cx, cy), Offset(v.x(note.endBeat ?: now), cy), strokeWidth = 2.dp.toPx())
        val tone = v.chordTone(note)
        if (tone) drawCircle(ink.copy(alpha = 0.25f), r * 2.2f, Offset(cx, cy))
        if (note === flash) {
            val pulse = 0.5f + 0.5f * sin(ms / 1000.0 * 2 * PI * 2).toFloat()
            drawCircle(ROOT_INK.copy(alpha = 0.35f + 0.3f * pulse), r * (2f + pulse), Offset(cx, cy))
            drawCircle(ROOT_INK, r * 1.4f, Offset(cx, cy))
        } else {
            drawCircle(if (tone || sounding) ink else ink.copy(alpha = 0.6f), if (tone) r * 1.2f else r * 0.85f, Offset(cx, cy))
        }
        // Above the star and clear of its glow, tinted toward the lane's ink.
        val label = measurer.measure(note.name, noteStyle.copy(color = lerp(MixerInk.text, ink, 0.35f)))
        drawText(label, topLeft = Offset(cx - label.size.width / 2f, cy - r * 2.4f - label.size.height))
    }
}

/** Reports [min] as its least height, so a scrolling child never makes the mixer ask the strips area for more. */
private fun Modifier.minIntrinsicHeight(min: Dp): Modifier = this.then(MinIntrinsicHeight(min))

private class MinIntrinsicHeight(val min: Dp) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) { p.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = min.roundToPx()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = min.roundToPx()

    override fun equals(other: Any?) = other is MinIntrinsicHeight && other.min == min

    override fun hashCode() = min.hashCode()
}

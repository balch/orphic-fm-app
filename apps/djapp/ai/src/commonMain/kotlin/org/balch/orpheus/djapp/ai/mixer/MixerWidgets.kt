package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.proportional
import org.balch.orpheus.ui.widgets.RotaryKnob

/** The bench's palette mapped onto theme tokens. Opaque on purpose: this is a reading surface. */
internal object MixerInk {
    val surface = OrpheusColors.deepPurple
    val card = OrpheusColors.panelSurfaceDark
    val sourceCard = OrpheusColors.darkVoid
    val well = OrpheusColors.galaxyBackground
    val line = OrpheusColors.outlineDark
    val chip = OrpheusColors.outlineVariantDark
    val accent = OrpheusColors.cosmicPurple
    val accentSoft = OrpheusColors.echoLavender
    val text = OrpheusColors.onSurfaceDark
    val dim = OrpheusColors.slateSilver
    val changed = OrpheusColors.mixerFxAmber
    val red = OrpheusColors.seqRed
    val green = OrpheusColors.engineGreen
    val meterGreen = OrpheusColors.strategyGreen
    val hot = OrpheusColors.mixerDistRed
    val onBright = OrpheusColors.almostBlack
    val tracks = OrpheusColors.pulsarTracks
    val agent = OrpheusColors.neonCyan
    val pin = OrpheusColors.echoLavender
}

/** Pills and chips, between the labels and the values. */
private const val PILL_SP = 12f

/** A strip knob's value while it is held. */
private const val READOUT_SP = 15f

/** The gap between two macro knobs. */
internal val MACRO_KNOB_GAP = 10.dp

/** A [MixerSlider]'s default row height. */
internal val SLIDER_HEIGHT = 18.dp

internal const val FADER_MIN_DB = -30f
internal const val FADER_MAX_DB = 3.5f
private const val METER_FLOOR_DB = -60.0
private val METER_TICKS_DB = doubleArrayOf(-6.0, -12.0, -24.0, -48.0)

/** Fader travel 0..1 for a dB offset, linear over the bench's -30..+3.5 dB. */
internal fun dbToFader(db: Float): Float = ((db - FADER_MIN_DB) / (FADER_MAX_DB - FADER_MIN_DB)).coerceIn(0f, 1f)

/** The dB offset at fader travel [pos], rounded to 0.1 dB like bench.js. */
internal fun faderToDb(pos: Float): Float =
    ((FADER_MIN_DB + pos.coerceIn(0f, 1f) * (FADER_MAX_DB - FADER_MIN_DB)) * 10f).roundToInt() / 10f

internal fun silent(db: Double): Boolean = db <= -119.0

/** Meter bar fill for a peak: a -60 dB floor, and 0 for the meters' silence value. */
internal fun meterFill(peakDb: Double): Float =
    if (silent(peakDb)) 0f else ((peakDb - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0.0, 1.0).toFloat()

/** The column says dB, so the number goes alone. */
internal fun dbText(db: Float): String = (if (db > 0f) "+" else "") + fixed(db, 1)

internal fun panText(p: Float): String {
    val n = (abs(p) * 100f).roundToInt()
    return if (n == 0) "C" else (if (p < 0f) "L" else "R") + n
}

internal fun luText(lu: Double): String = if (silent(lu)) "-" else fixed(lu.toFloat(), 1)

internal fun faderTag(index: Int) = "mixer-fader-$index"

internal fun macroTag(macro: Macro) = "mixer-macro-${macro.name}"

internal fun pinTag(index: Int) = "mixer-pin-$index"

/** The fader's least height: 72dp of travel inside its 6dp padding. */
internal val MIN_FADER = 84.dp

internal val NO_WAVE = FloatArray(0)

/** What the panel shows before the first meter poll lands. */
internal val EMPTY_METERS = MeterSnapshot(
    lufsShort = -120.0,
    lufsIntegrated = -120.0,
    preClipPeakDb = -120.0,
    pastKneePct = 0.0,
    correlation = 0.0,
    channels = emptyList(),
    section = -1,
)

internal fun mixText(
    size: TextUnit,
    color: Color = MixerInk.text,
    weight: FontWeight? = null,
    mono: Boolean = false,
): TextStyle = TextStyle(
    fontSize = size,
    color = color,
    fontWeight = weight,
    fontFamily = if (mono) FontFamily.Monospace else null,
).proportional()

/** What an agent edit lights up: a track's strip or row, a macro knob, or a section control. */
internal sealed interface FlashTarget {
    data class Track(val index: Int) : FlashTarget
    data class Knob(val macro: Macro) : FlashTarget
    data object Section : FlashTarget
    data object Loop : FlashTarget
    data object EditScope : FlashTarget
}

/** A count per target of the agent's and MCP's edits; each new count replays that target's flash. */
@Stable
internal class AgentFlashes {
    private val counts = mutableStateMapOf<FlashTarget, Int>()

    /** A reset flashes only [unpinned]: the agent's and MCP's resets skip pinned strips. */
    fun flash(control: MixControl, unpinned: Collection<Int>) {
        when (control) {
            is MixControl.MacroKnob -> bump(FlashTarget.Knob(control.macro))
            MixControl.Section -> bump(FlashTarget.Section)
            MixControl.Loop -> bump(FlashTarget.Loop)
            MixControl.EditScope -> bump(FlashTarget.EditScope)
            MixControl.ClearAll -> unpinned.forEach { bump(FlashTarget.Track(it)) }
            else -> control.track?.let { bump(FlashTarget.Track(it)) }
        }
    }

    private fun bump(target: FlashTarget) {
        counts[target] = (counts[target] ?: 0) + 1
    }

    fun count(target: FlashTarget): Int = counts[target] ?: 0

    fun track(index: Int): Int = count(FlashTarget.Track(index))
}

/** The factor [legible] divides out, with the same clamp: 1 at native density, larger on a shrunk canvas. */
@Composable
internal fun textScale(): Float = 1f / LocalCanvasScale.current.coerceIn(0.5f, 1f)

internal const val FLASH_MS = 600

internal val AgentFlashing = SemanticsPropertyKey<Boolean>("AgentFlashing")
internal val AgentGlow = SemanticsPropertyKey<Boolean>("AgentGlow")

/**
 * The agent's marks on a control: a [FLASH_MS] highlight each time [flashKey] moves on, and a faint [glow] while
 * set. Drawn over the content, so it shows on any background.
 */
@Composable
internal fun agentMark(flashKey: Int, glow: Boolean, corner: Dp): Modifier {
    val flash = remember { Animatable(0f) }
    // A count already standing when this composes (a row scrolled back in) is not a new edit.
    val first = remember { flashKey }
    LaunchedEffect(flashKey) {
        if (flashKey == first) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(FLASH_MS, easing = LinearEasing))
    }
    val flashing by remember { derivedStateOf { flash.value > 0f } }
    val isFlashing = flashing
    return Modifier
        .semantics {
            this[AgentFlashing] = isFlashing
            this[AgentGlow] = glow
        }
        .drawWithContent {
            drawContent()
            val r = CornerRadius(corner.toPx())
            if (glow) {
                drawRoundRect(MixerInk.agent.copy(alpha = 0.08f), cornerRadius = r)
                drawRoundRect(MixerInk.agent.copy(alpha = 0.45f), cornerRadius = r, style = Stroke(1.dp.toPx()))
            }
            val f = flash.value
            if (f > 0f) {
                drawRoundRect(MixerInk.agent.copy(alpha = 0.3f * f), cornerRadius = r)
                drawRoundRect(MixerInk.agent.copy(alpha = f), cornerRadius = r, style = Stroke(2.dp.toPx()))
            }
        }
}

/** A pin's least touch target, larger than its icon. */
internal val PIN_TARGET = 32.dp

/**
 * A strip's or row's pin: filled while the agent must leave that track alone. It takes [size] in the layout, but
 * its [PIN_TARGET] touch target spills past that, so the header does not grow. [anchor] says which way it spills:
 * a pin in a clipped corner anchors there, so the clip never trims the target.
 */
@Composable
internal fun PinToggle(
    index: Int,
    name: String,
    pinned: Boolean,
    onToggle: () -> Unit,
    size: Dp = 18.dp,
    anchor: Alignment = Alignment.Center,
) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .wrapContentSize(anchor, unbounded = true)
                .size(max(size, PIN_TARGET))
                .testTag(pinTag(index))
                .semantics { contentDescription = if (pinned) "Unpin $name" else "Pin $name" }
                .toggleable(value = pinned, role = Role.Switch, onValueChange = { onToggle() }),
            contentAlignment = anchor,
        ) {
            Icon(
                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = null,
                tint = if (pinned) MixerInk.pin else MixerInk.dim,
                modifier = Modifier.padding(1.dp).size(size - 2.dp),
            )
        }
    }
}

/**
 * Nothing changes on down. Movement past touch slop along [vertical]'s axis claims the drag, which follows the
 * pointer and holds its value in [hold] until release; slop on the other axis, or a parent consuming first, leaves
 * the gesture to the parent (the list scroll). A tap within slop sets the value there, and a second tap within the
 * double-tap timeout calls [onDoubleTap] instead. Keyed on [key] (the track index).
 */
private fun Modifier.dragHold(
    key: Any,
    vertical: Boolean,
    hold: MutableState<Float?>,
    toValue: State<(Offset, IntSize) -> Float>,
    onChange: State<(Float) -> Unit>,
    onDoubleTap: State<(() -> Unit)?>,
): Modifier = pointerInput(key) {
    var lastTapUp = -1L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fun set(p: Offset) {
            val v = toValue.value(p, size)
            if (v != hold.value) {
                hold.value = v
                onChange.value(v)
            }
        }
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (c.isConsumed) return@awaitEachGesture
            if (!c.pressed) {
                c.consume()
                val doubleTap = onDoubleTap.value
                if (doubleTap != null && lastTapUp >= 0 &&
                    down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis
                ) {
                    lastTapUp = -1L
                    doubleTap()
                } else {
                    lastTapUp = c.uptimeMillis
                    set(c.position)
                    hold.value = null
                }
                return@awaitEachGesture
            }
            moved += c.positionChange()
            val along = abs(if (vertical) moved.y else moved.x)
            val across = abs(if (vertical) moved.x else moved.y)
            if (along > slop) {
                c.consume()
                set(c.position)
                break
            }
            if (across > slop) return@awaitEachGesture
        }
        lastTapUp = -1L
        try {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) {
                    c.consume()
                    break
                }
                set(c.position)
                c.consume()
            }
        } finally {
            hold.value = null
        }
    }
}

/** A track's last 2 s as one rect per (min, max) bin, scaled to its own peak like bench.js `drawMini`. */
@Composable
internal fun MixerWave(wave: () -> FloatArray, color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MixerInk.well)
            .drawBehind { drawWave(wave(), color) },
    )
}

private fun DrawScope.drawWave(w: FloatArray, color: Color) {
    if (w.size < 2) return
    var peak = 0.02f
    for (v in w) peak = max(peak, abs(v))
    val bins = w.size / 2
    val mid = size.height / 2f
    val s = (size.height / 2f - 1f) / peak
    for (j in 0 until bins) {
        val x = floor(j.toFloat() / bins * size.width)
        val x2 = floor((j + 1f) / bins * size.width)
        val y0 = mid - w[2 * j + 1] * s
        val y1 = mid - w[2 * j] * s
        drawRect(color, Offset(x, y0), Size(max(1f, x2 - x), max(1f, y1 - y0)))
    }
}

/** A peak bar, green to amber to red, with ticks at -6, -12, -24 and -48 dB. [fill] is read while drawing. */
@Composable
internal fun MixerMeter(fill: () -> Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    Spacer(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MixerInk.well)
            .drawWithCache {
                val stops = arrayOf(
                    0f to MixerInk.meterGreen,
                    0.7f to MixerInk.meterGreen,
                    0.85f to MixerInk.changed,
                    1f to MixerInk.hot,
                )
                val brush = if (vertical) {
                    Brush.verticalGradient(*stops, startY = size.height, endY = 0f)
                } else {
                    Brush.horizontalGradient(*stops, startX = 0f, endX = size.width)
                }
                val tick = 1.dp.toPx()
                onDrawBehind {
                    val v = fill().coerceIn(0f, 1f)
                    val w = size.width
                    val h = size.height
                    if (vertical) {
                        drawRect(brush, Offset(0f, h * (1f - v)), Size(w, h * v))
                        for (db in METER_TICKS_DB) drawRect(MixerInk.line, Offset(0f, h * (db / METER_FLOOR_DB).toFloat()), Size(w, tick))
                    } else {
                        drawRect(brush, Offset.Zero, Size(w * v, h))
                        for (db in METER_TICKS_DB) drawRect(MixerInk.line, Offset(w * (1f - (db / METER_FLOOR_DB).toFloat()), 0f), Size(tick, h))
                    }
                }
            },
    )
}

/**
 * The level fader: [db] is the offset from the file. The thumb follows the pointer over the full height, as the
 * bench's does; a drag holds and shows its own value until release. Double tap returns to 0 dB via [onReset].
 * [meter] draws beside the track.
 */
@Composable
internal fun DbFader(
    db: Float,
    onDbChange: (Float) -> Unit,
    onReset: () -> Unit,
    index: Int,
    modifier: Modifier = Modifier,
    height: Dp? = 140.dp,
    meter: (@Composable () -> Unit)? = null,
) {
    val hold = remember { mutableStateOf<Float?>(null) }
    val shown = hold.value ?: db
    val changed = faderChanged(hold.value, db)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // A null height takes what the column is given, at least MIN_FADER.
        Row(
            (if (height != null) Modifier.height(height) else Modifier.weight(1f).heightIn(min = MIN_FADER)).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            meter?.invoke()
            FaderTrack(index, vertical = true, hold, shown, changed, onDbChange, onReset, Modifier.width(26.dp).fillMaxHeight())
        }
        Text(dbText(shown), style = mixText(legible(LegibleText.MIXER_VALUE), if (changed) MixerInk.changed else MixerInk.text), maxLines = 1)
    }
}

/** [DbFader] laid on its side for the compact rows: label, track (left is -30 dB), then the dB value. */
@Composable
internal fun DbFaderRow(
    db: Float,
    onDbChange: (Float) -> Unit,
    onReset: () -> Unit,
    index: Int,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 34.dp,
    valueWidth: Dp = 52.dp,
) {
    val hold = remember { mutableStateOf<Float?>(null) }
    val shown = hold.value ?: db
    val changed = faderChanged(hold.value, db)
    Row(modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("level", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1, modifier = Modifier.width(labelWidth))
        FaderTrack(index, vertical = false, hold, shown, changed, onDbChange, onReset, Modifier.weight(1f).fillMaxHeight())
        Text(
            dbText(shown),
            style = mixText(legible(LegibleText.MIXER_VALUE), if (changed) MixerInk.changed else MixerInk.text),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

private fun faderChanged(held: Float?, db: Float) = held != null || abs(db) > 0.05f

/** The fader's track, 0 dB tick and thumb (amber when [changed]), shared by both fader orientations. */
@Composable
private fun FaderTrack(
    index: Int,
    vertical: Boolean,
    hold: MutableState<Float?>,
    shown: Float,
    changed: Boolean,
    onDbChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val change = rememberUpdatedState(onDbChange)
    val reset = rememberUpdatedState<(() -> Unit)?>(onReset)
    val toDb = rememberUpdatedState<(Offset, IntSize) -> Float>(
        if (vertical) { p, s -> faderToDb(1f - p.y / s.height) } else { p, s -> faderToDb(p.x / s.width) },
    )
    val thumb = if (changed) MixerInk.changed else MixerInk.accentSoft
    Box(
        modifier
            .testTag(faderTag(index))
            .dragHold(index, vertical, hold, toDb, change, reset)
            .drawBehind { drawFader(dbToFader(shown), thumb, vertical) },
    )
}

/** Travel [pos] runs bottom to top when [vertical], left to right otherwise. */
private fun DrawScope.drawFader(pos: Float, thumb: Color, vertical: Boolean) {
    val track = 6.dp.toPx()
    val tick = 16.dp.toPx()
    val tl = 24.dp.toPx()
    val tw = 10.dp.toPx()
    val corner = CornerRadius(3.dp.toPx())
    val zero = dbToFader(0f)
    if (vertical) {
        val c = size.width / 2f
        drawRoundRect(MixerInk.well, Offset(c - track / 2f, 0f), Size(track, size.height), corner)
        drawRect(MixerInk.line, Offset(c - tick / 2f, size.height * (1f - zero)), Size(tick, 1.dp.toPx()))
        drawRoundRect(thumb, Offset(c - tl / 2f, size.height * (1f - pos) - tw / 2f), Size(tl, tw), corner)
    } else {
        val c = size.height / 2f
        drawRoundRect(MixerInk.well, Offset(0f, c - track / 2f), Size(size.width, track), corner)
        drawRect(MixerInk.line, Offset(size.width * zero, c - tick / 2f), Size(1.dp.toPx(), tick))
        val x = (size.width * pos - tw / 2f).coerceIn(0f, max(0f, size.width - tw))
        drawRoundRect(thumb, Offset(x, c - tl / 2f), Size(tw, tl), corner)
    }
}

/**
 * A one-line labelled slider over [range], like the bench's range inputs: press sets, drag follows, and the
 * dragged value shows until release. [onReset], if set, runs on double tap.
 */
@Composable
internal fun MixerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: (Float) -> String,
    index: Int,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    color: Color = MixerInk.accent,
    labelWidth: Dp = 18.dp,
    valueWidth: Dp = 26.dp,
    height: Dp = SLIDER_HEIGHT,
) {
    val hold = remember { mutableStateOf<Float?>(null) }
    val change = rememberUpdatedState(onValueChange)
    val reset = rememberUpdatedState(onReset)
    val shown = hold.value ?: value
    val span = range.endInclusive - range.start
    val origin = if (range.start < 0f && range.endInclusive > 0f) 0f else range.start
    val toValue = rememberUpdatedState<(Offset, IntSize) -> Float> { p, s ->
        val frac = (p.x / s.width).coerceIn(0f, 1f)
        ((range.start + frac * span) * 100f).roundToInt() / 100f
    }
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1, modifier = Modifier.width(labelWidth))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .dragHold(index, vertical = false, hold, toValue, change, reset)
                .drawBehind {
                    val cy = size.height / 2f
                    val th = 3.dp.toPx()
                    drawRoundRect(MixerInk.well, Offset(0f, cy - th / 2f), Size(size.width, th), CornerRadius(th / 2f))
                    val xo = size.width * (origin - range.start) / span
                    val xv = size.width * (shown.coerceIn(range.start, range.endInclusive) - range.start) / span
                    drawRect(color, Offset(minOf(xo, xv), cy - th / 2f), Size(abs(xv - xo), th))
                    val r = 4.dp.toPx()
                    drawCircle(MixerInk.accentSoft, r, Offset(xv.coerceIn(r, size.width - r), cy))
                },
        )
        Text(
            valueText(shown),
            style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

@Composable
internal fun MuteSoloButtons(
    muted: Boolean,
    soloed: Boolean,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallToggle("M", muted, MixerInk.hot, onMute)
        SmallToggle("S", soloed, MixerInk.changed, onSolo)
    }
}

@Composable
private fun SmallToggle(text: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) onColor else MixerInk.chip)
            .clickable(onClick = onClick)
            .sizeIn(minWidth = 24.dp, minHeight = 20.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mixText(legible(LegibleText.MIXER_MIN), if (on) MixerInk.onBright else MixerInk.text, FontWeight.Bold), maxLines = 1)
    }
}

/**
 * A strip's small pan or send knob. While held, [onHeld] gets the value under the finger (null on release) so the
 * strip can show it large; double tap runs [onReset].
 */
@Composable
internal fun StripKnob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onHeld: (Float?) -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    // RotaryKnob keeps its dragged value until its input changes, so a release re-keys it onto the state.
    var generation by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val held by rememberUpdatedState(onHeld)
    val reset by rememberUpdatedState(onReset)
    Box(
        modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) held(current)
                        if (e.type == PointerEventType.Release && e.changes.none { it.pressed }) {
                            held(null)
                            generation++
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset?.invoke() }) },
    ) {
        key(generation) {
            RotaryKnob(
                value = value,
                onValueChange = {
                    held(it)
                    change(it)
                },
                label = label,
                labelStyle = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
                labelColor = MixerInk.dim,
                range = range,
                size = size,
                progressColor = MixerInk.accent,
                indicatorColor = MixerInk.accentSoft,
                valueFormatter = null,
                requireVerticalSlop = true,
            )
        }
    }
}

/** A held knob's value, large, over the strip's wave. */
@Composable
internal fun KnobReadout(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = mixText(legible(READOUT_SP), MixerInk.text, FontWeight.Bold),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MixerInk.surface)
            .padding(horizontal = 4.dp),
    )
}

/**
 * The four macro knobs. A changed knob turns amber and its note reads "← 0.50", where reset lands; double tap
 * resets. A drag shows its own value and snaps back to the state on release.
 */
@Composable
internal fun MixerMacroKnobs(
    macros: List<MixerMacro>,
    onSet: (Macro, Float) -> Unit,
    modifier: Modifier = Modifier,
    knobSize: Dp = 46.dp,
    knobWidth: Dp = 88.dp,
    flashes: AgentFlashes? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MACRO_KNOB_GAP)) {
        for (m in macros) {
            key(m.macro) { MacroKnob(m, onSet, knobSize, knobWidth, flashes?.count(FlashTarget.Knob(m.macro)) ?: 0) }
        }
    }
}

@Composable
private fun MacroKnob(m: MixerMacro, onSet: (Macro, Float) -> Unit, knobSize: Dp, knobWidth: Dp, flashKey: Int) {
    // RotaryKnob keeps its dragged value until its input changes, so a release re-keys it onto the state.
    var generation by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf<Float?>(null) }
    val set by rememberUpdatedState(onSet)
    val current by rememberUpdatedState(m)
    val color = if (m.changed) MixerInk.changed else MixerInk.accent
    val mark = agentMark(flashKey, glow = false, corner = 8.dp)
    Column(Modifier.width(knobWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pointerInput(m.macro) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Release && e.changes.none { it.pressed }) {
                                held = null
                                generation++
                            }
                        }
                    }
                }
                .pointerInput(m.macro) {
                    detectTapGestures(onDoubleTap = { current.let { set(it.macro, it.resetValue) } })
                },
        ) {
            key(generation) {
                RotaryKnob(
                    value = m.value,
                    onValueChange = {
                        held = it
                        set(m.macro, it)
                    },
                    label = m.macro.name,
                    labelStyle = mixText(legible(LegibleText.MIXER_MIN), weight = FontWeight.SemiBold),
                    size = knobSize,
                    progressColor = color,
                    indicatorColor = color,
                    // Its own value text has a fixed size; the legible one is drawn below.
                    valueFormatter = null,
                    // In the phone list a touch that starts here may be a scroll: nothing turns before slop.
                    requireVerticalSlop = true,
                    modifier = Modifier.testTag(macroTag(m.macro)).then(mark),
                )
            }
        }
        Text(
            f2(held ?: m.value),
            style = mixText(legible(LegibleText.MIXER_VALUE), if (m.changed) MixerInk.changed else MixerInk.text),
            maxLines = 1,
        )
        Text(
            listOfNotNull(if (m.changed) "← ${f2(m.resetValue)}" else null, m.note.ifEmpty { null }).joinToString(" · "),
            style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
            // Small knobs mean a short window: the note keeps to one line there.
            maxLines = if (knobSize < 40.dp) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The Loop toggle, then the arrangement's sections in a scrolling row. The playing chip is filled, the looped one
 * outlined amber; a tap jumps at the next bar line.
 */
@Composable
internal fun SectionChips(
    sections: List<String>,
    playing: Int,
    looped: Int?,
    onJump: (Int) -> Unit,
    onLoop: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    flashes: AgentFlashes? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val looping = looped != null
        Pill(
            modifier = agentMark(flashes?.count(FlashTarget.Loop) ?: 0, glow = false, corner = 12.dp),
            text = if (looping) "Looping " + (sections.getOrNull(looped) ?: "section") else "Loop section",
            fill = if (looping) MixerInk.changed else MixerInk.chip,
            textColor = if (looping) MixerInk.onBright else MixerInk.text,
            border = MixerInk.line,
            maxWidth = 180.dp,
            onClick = { onLoop(!looping) },
        )
        // A jump lands at the next bar line, so the agent's jump flashes the whole row, not one chip.
        LazyRow(
            Modifier.weight(1f).then(agentMark(flashes?.count(FlashTarget.Section) ?: 0, glow = false, corner = 12.dp)),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(sections) { i, name ->
                Pill(
                    text = name,
                    fill = if (i == playing) MixerInk.accent else MixerInk.chip,
                    textColor = if (i == playing) MixerInk.text else MixerInk.dim,
                    border = if (i == looped) MixerInk.changed else Color.Transparent,
                    maxWidth = 160.dp,
                    onClick = { onJump(i) },
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Pill(
    text: String,
    fill: Color,
    textColor: Color,
    border: Color,
    maxWidth: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = mixText(legible(PILL_SP), if (enabled) textColor else textColor.copy(alpha = 0.35f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Whole vibe or the playing section (named); the section side is disabled when the vibe has under two sections. */
@Composable
internal fun ScopeToggle(state: AiMixerUiState, onScope: (Scope) -> Unit, modifier: Modifier = Modifier, flashKey: Int = 0) {
    val available = state.sectionScopeAvailable
    val sectionOn = state.scope == Scope.SECTION && available
    val name = if (available && state.section >= 0) state.sections.getOrNull(state.section) ?: "section" else "section"
    Row(
        modifier.then(agentMark(flashKey, glow = false, corner = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("editing:", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1)
        Pill(
            text = "whole vibe",
            fill = if (!sectionOn) MixerInk.accent else MixerInk.chip,
            textColor = MixerInk.text,
            border = MixerInk.line,
            maxWidth = 120.dp,
            onClick = { onScope(Scope.VIBE) },
        )
        Pill(
            text = name,
            fill = if (sectionOn) MixerInk.accent else MixerInk.chip,
            textColor = MixerInk.text,
            border = MixerInk.line,
            maxWidth = 140.dp,
            enabled = available,
            onClick = { onScope(Scope.SECTION) },
        )
    }
}

/**
 * The bus line from the OUT channel: short-term and integrated LUFS, then pre-clip dBFS (red above -1), past-knee
 * share and correlation. Its own leaf, so a meter tick recomposes only this text.
 */
@Composable
internal fun BusReadout(
    meters: State<MeterSnapshot>,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.End,
    nativeSize: Float = LegibleText.MIXER_VALUE,
) {
    val size = legible(nativeSize)
    val liveSize = legible(LegibleText.MIXER_MIN)
    val text by remember(meters, liveSize) { derivedStateOf { busText(meters.value, liveSize) } }
    Text(text, style = mixText(size, MixerInk.dim).copy(lineHeight = 1.5.em), textAlign = textAlign, modifier = modifier)
}

private fun busText(m: MeterSnapshot, liveSize: TextUnit): AnnotatedString = buildAnnotatedString {
    fun lu(x: Double) = if (silent(x)) "-" else fixed(x.toFloat(), 1)
    fun value(s: String, color: Color = MixerInk.text) {
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(s) }
    }
    fun live() {
        append(" ")
        withStyle(SpanStyle(fontSize = liveSize, background = MixerInk.chip, color = MixerInk.dim)) { append(" live ") }
    }
    append("LUFS short ")
    value(lu(m.lufsShort))
    live()
    append(" · integrated ")
    value(lu(m.lufsIntegrated))
    live()
    append("\npre-clip ")
    value(lu(m.preClipPeakDb) + " dBFS", if (m.preClipPeakDb > -1.0) MixerInk.hot else MixerInk.text)
    append(" · past knee ")
    value(fixed(m.pastKneePct.toFloat(), 1) + "%")
    append(" · corr ")
    value(fixed(m.correlation.toFloat(), 2))
}

/** Channel [ch]'s loudness against the dry mix; a leaf that recomposes only when its text changes. */
@Composable
internal fun LuText(meters: State<MeterSnapshot>, ch: Int, modifier: Modifier = Modifier) {
    val text by remember(meters, ch) {
        derivedStateOf { meters.value.channels.getOrNull(ch)?.let { luText(it.relLu) } ?: "-" }
    }
    Text(text, style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.dim), maxLines = 1, modifier = modifier)
}

/** Channel [ch]'s peak in dBFS, for the DRY and OUT meters. */
@Composable
internal fun PeakText(meters: State<MeterSnapshot>, ch: Int, modifier: Modifier = Modifier) {
    val text by remember(meters, ch) {
        derivedStateOf {
            val pk = meters.value.channels.getOrNull(ch)?.peakDb ?: -120.0
            if (silent(pk)) "-" else fixed(pk.toFloat(), 1)
        }
    }
    Text(text, style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.dim), maxLines = 1, modifier = modifier)
}

/** The pending changes as bench.js lists them: grey headers, red old values, green new ones, amber flags. */
@Composable
internal fun ChangesList(lines: List<ChangeLine>, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (lines.isEmpty()) {
            Text("No changes. Move a fader, pan, send or knob.", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim))
            return@Column
        }
        for (line in lines) {
            val color = when (line.kind) {
                ChangeKind.HEADER -> MixerInk.dim
                ChangeKind.OLD -> MixerInk.red
                ChangeKind.NEW -> MixerInk.green
                ChangeKind.FLAG -> MixerInk.changed
            }
            Text(
                line.text,
                style = mixText(legible(LegibleText.MIXER_MIN), color, mono = true),
                modifier = if (line.kind == ChangeKind.HEADER) Modifier.padding(top = 5.dp) else Modifier,
            )
        }
    }
}

@Composable
internal fun MixerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) MixerInk.accent else MixerInk.chip)
            .border(1.dp, if (primary) MixerInk.accent else MixerInk.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mixText(legible(LegibleText.MIXER_VALUE), if (enabled) MixerInk.text else MixerInk.dim), maxLines = 1)
    }
}

/** The capture outcome as the bench words it; red when it failed or the vibe file went stale. */
@Composable
internal fun CaptureResult(capture: MixerCapture, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = if (capture.ok && capture.file != null) {
        "Wrote ${capture.rows} rows to ${capture.file}" +
            (if (capture.stale == true) " (STALE: the vibe file changed since load)" else "")
    } else {
        capture.message
    }
    val bad = !capture.ok || capture.stale == true
    // One line, so a capture never pushes the strips down; the semantics keep the whole text.
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text,
            style = mixText(legible(LegibleText.MIXER_MIN), if (bad) MixerInk.red else MixerInk.green),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "×",
            style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.dim, FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier
                .testTag(CAPTURE_DISMISS_TAG)
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "Dismiss" }
                .padding(horizontal = 4.dp),
        )
    }
}

internal const val CAPTURE_DISMISS_TAG = "mixer-capture-dismiss"

/** A refused edit or send note, with a dismiss. */
@Composable
internal fun NoticeLine(notice: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MixerInk.well)
            .border(1.dp, MixerInk.changed, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(notice, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.changed), modifier = Modifier.weight(1f))
        Text(
            "Dismiss",
            style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.text, FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
        )
    }
}

/** The track's wave samples, read inside a draw lambda. */
internal fun State<MeterSnapshot>.waveOf(ch: Int): FloatArray = value.channels.getOrNull(ch)?.wave ?: NO_WAVE

/** The track's meter fill, read inside a draw lambda. */
internal fun State<MeterSnapshot>.fillOf(ch: Int): Float = value.channels.getOrNull(ch)?.let { meterFill(it.peakDb) } ?: 0f


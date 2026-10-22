package org.balch.orpheus.djapp.ai.mixer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
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
}

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

internal fun dbText(db: Float): String = (if (db > 0f) "+" else "") + fixed(db, 1) + " dB"

internal fun panText(p: Float): String {
    val n = (abs(p) * 100f).roundToInt()
    return if (n == 0) "C" else (if (p < 0f) "L" else "R") + n
}

internal fun luText(lu: Double): String = if (silent(lu)) "- LU" else fixed(lu.toFloat(), 1) + " LU"

internal fun faderTag(index: Int) = "mixer-fader-$index"

internal fun macroTag(macro: Macro) = "mixer-macro-${macro.name}"

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
    height: Dp = 140.dp,
    meter: (@Composable () -> Unit)? = null,
) {
    val hold = remember { mutableStateOf<Float?>(null) }
    val shown = hold.value ?: db
    val changed = faderChanged(hold.value, db)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.height(height).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            meter?.invoke()
            FaderTrack(index, vertical = true, hold, shown, changed, onDbChange, onReset, Modifier.width(26.dp).fillMaxHeight())
        }
        Text(dbText(shown), style = mixText(10.sp, if (changed) MixerInk.changed else MixerInk.text), maxLines = 1)
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
        Text("level", style = mixText(9.sp, MixerInk.dim), maxLines = 1, modifier = Modifier.width(labelWidth))
        FaderTrack(index, vertical = false, hold, shown, changed, onDbChange, onReset, Modifier.weight(1f).fillMaxHeight())
        Text(
            dbText(shown),
            style = mixText(10.sp, if (changed) MixerInk.changed else MixerInk.text),
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
        Text(label, style = mixText(9.sp, MixerInk.dim), maxLines = 1, modifier = Modifier.width(labelWidth))
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
            style = mixText(9.sp, MixerInk.text),
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
            .size(width = 24.dp, height = 20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) onColor else MixerInk.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mixText(10.sp, if (on) MixerInk.onBright else MixerInk.text, FontWeight.Bold))
    }
}

/**
 * The four macro knobs. A changed knob turns amber and reads "0.60 ← 0.50" (value, then where reset lands);
 * double tap resets. A drag shows its own value and snaps back to the state on release.
 */
@Composable
internal fun MixerMacroKnobs(
    macros: List<MixerMacro>,
    onSet: (Macro, Float) -> Unit,
    modifier: Modifier = Modifier,
    knobSize: Dp = 46.dp,
    knobWidth: Dp = 88.dp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in macros) key(m.macro) { MacroKnob(m, onSet, knobSize, knobWidth) }
    }
}

@Composable
private fun MacroKnob(m: MixerMacro, onSet: (Macro, Float) -> Unit, knobSize: Dp, knobWidth: Dp) {
    // RotaryKnob keeps its dragged value until its input changes, so a release re-keys it onto the state.
    var generation by remember { mutableIntStateOf(0) }
    val set by rememberUpdatedState(onSet)
    val current by rememberUpdatedState(m)
    val color = if (m.changed) MixerInk.changed else MixerInk.accent
    Column(Modifier.width(knobWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pointerInput(m.macro) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Release && e.changes.none { it.pressed }) generation++
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
                    onValueChange = { set(m.macro, it) },
                    label = m.macro.name,
                    labelStyle = mixText(10.sp, weight = FontWeight.SemiBold),
                    size = knobSize,
                    progressColor = color,
                    indicatorColor = color,
                    valueFormatter = { v -> if (current.changed) "${f2(v)} ← ${f2(current.resetValue)}" else f2(v) },
                    // In the phone list a touch that starts here may be a scroll: nothing turns before slop.
                    requireVerticalSlop = true,
                    modifier = Modifier.testTag(macroTag(m.macro)),
                )
            }
        }
        Text(
            m.note,
            style = mixText(9.sp, MixerInk.dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val looping = looped != null
        Pill(
            text = if (looping) "Looping " + (sections.getOrNull(looped ?: -1) ?: "section") else "Loop section",
            fill = if (looping) MixerInk.changed else MixerInk.chip,
            textColor = if (looping) MixerInk.onBright else MixerInk.text,
            border = MixerInk.line,
            maxWidth = 180.dp,
            onClick = { onLoop(!looping) },
        )
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            style = mixText(12.sp, if (enabled) textColor else textColor.copy(alpha = 0.35f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Whole vibe or the playing section (named); the section side is disabled when the vibe has under two sections. */
@Composable
internal fun ScopeToggle(state: AiMixerUiState, onScope: (Scope) -> Unit, modifier: Modifier = Modifier) {
    val available = state.sectionScopeAvailable
    val sectionOn = state.scope == Scope.SECTION && available
    val name = if (available && state.section >= 0) state.sections.getOrNull(state.section) ?: "section" else "section"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("editing:", style = mixText(11.sp, MixerInk.dim), maxLines = 1)
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
    size: TextUnit = 14.sp,
) {
    val text by remember(meters, size) { derivedStateOf { busText(meters.value, size) } }
    Text(text, style = mixText(size, MixerInk.dim).copy(lineHeight = 1.5.em), textAlign = textAlign, modifier = modifier)
}

private fun busText(m: MeterSnapshot, size: TextUnit): AnnotatedString = buildAnnotatedString {
    fun lu(x: Double) = if (silent(x)) "-" else fixed(x.toFloat(), 1)
    fun value(s: String, color: Color = MixerInk.text) {
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(s) }
    }
    fun live() {
        append(" ")
        withStyle(SpanStyle(fontSize = size * 0.8f, background = MixerInk.chip, color = MixerInk.dim)) { append(" live ") }
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
        derivedStateOf { meters.value.channels.getOrNull(ch)?.let { luText(it.relLu) } ?: "- LU" }
    }
    Text(text, style = mixText(9.sp, MixerInk.dim), maxLines = 1, modifier = modifier)
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
    Text(text, style = mixText(9.sp, MixerInk.dim), maxLines = 1, modifier = modifier)
}

/** The pending changes as bench.js lists them: grey headers, red old values, green new ones, amber flags. */
@Composable
internal fun ChangesList(lines: List<ChangeLine>, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (lines.isEmpty()) {
            Text("No changes. Move a fader, pan, send or knob.", style = mixText(11.sp, MixerInk.dim))
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
                style = mixText(10.5.sp, color, mono = true),
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
        Text(text, style = mixText(13.sp, if (enabled) MixerInk.text else MixerInk.dim), maxLines = 1)
    }
}

/** The capture outcome as the bench words it; red when it failed or the vibe file went stale. */
@Composable
internal fun CaptureResult(capture: MixerCapture, modifier: Modifier = Modifier) {
    val text = if (capture.ok && capture.file != null) {
        "Wrote ${capture.rows} rows to ${capture.file}" +
            (if (capture.appliedToAiVibe) "; the AI vibe now holds them, so the song restarts and mutes, solos and the loop clear" else "") +
            (if (capture.stale == true) " (STALE: the vibe file changed since load)" else "")
    } else {
        capture.message
    }
    val bad = !capture.ok || capture.stale == true
    Text(text, style = mixText(11.sp, if (bad) MixerInk.red else MixerInk.green), modifier = modifier)
}

/** A refused edit or send note, with a dismiss. */
@Composable
internal fun NoticeLine(notice: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MixerInk.well)
            .border(1.dp, MixerInk.changed, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(notice, style = mixText(11.sp, MixerInk.changed), modifier = Modifier.weight(1f))
        Text(
            "Dismiss",
            style = mixText(11.sp, MixerInk.text, FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
        )
    }
}

/** The track's wave samples, read inside a draw lambda. */
internal fun State<MeterSnapshot>.waveOf(ch: Int): FloatArray = value.channels.getOrNull(ch)?.wave ?: NO_WAVE

/** The track's meter fill, read inside a draw lambda. */
internal fun State<MeterSnapshot>.fillOf(ch: Int): Float = value.channels.getOrNull(ch)?.let { meterFill(it.peakDb) } ?: 0f


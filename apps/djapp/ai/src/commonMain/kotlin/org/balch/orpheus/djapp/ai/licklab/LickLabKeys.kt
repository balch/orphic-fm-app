package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.mixer.MixerInk
import org.balch.orpheus.djapp.ai.mixer.mixText
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

internal const val LICK_LAB_TAG = "lick-lab"
internal const val KEY_STRIP_TAG = "lick-lab-keys"

internal fun stripKeyTag(index: Int) = "lick-lab-key-$index"

private val SHIFTS = setOf(Key.ShiftLeft, Key.ShiftRight)

/** The chromatic map's top-row keys, drawn as black keys. */
private val BLACK = setOf(Key.W, Key.E, Key.T, Key.Y, Key.U, Key.O, Key.P)

private val KEY_LABELS = mapOf(
    Key.A to "A", Key.S to "S", Key.D to "D", Key.F to "F", Key.G to "G", Key.H to "H", Key.J to "J", Key.K to "K",
    Key.L to "L", Key.Semicolon to ";", Key.Apostrophe to "'",
    Key.Q to "Q", Key.W to "W", Key.E to "E", Key.R to "R", Key.T to "T", Key.Y to "Y", Key.U to "U", Key.I to "I",
    Key.O to "O", Key.P to "P",
    Key.Z to "Z", Key.X to "X", Key.C to "C", Key.V to "V", Key.B to "B", Key.N to "N", Key.M to "M",
)

/** A full key's height of vertical drag bends this far; the engine caps it at ±2 semitones. */
private const val BEND_PER_KEY = 2f

/** Drag this far before a bend starts, so a sideways slide does not wobble the pitch. */
private val BEND_DEAD_ZONE = 4.dp

/** One on-screen key: the note it plays in the vibe's own key, its Mac keys and its degree (null off the scale). */
@Immutable
internal data class StripKey(val midi: Int, val macKeys: String, val degree: Int?, val black: Boolean)

/** The strip for [mode], low to high: degrees -7..16 in key, or the piano layout's 18 semitones. */
internal fun stripKeys(mode: KeyMode, baseMidi: Int, scale: List<Int>): List<StripKey> {
    val byOffset = LickKeyMap.keys(mode).entries.groupBy({ it.value }, { it.key })
    return byOffset.keys.sorted().map { offset ->
        val keys = byOffset.getValue(offset)
        val label = keys.joinToString(" ") { KEY_LABELS[it] ?: "?" }
        when (mode) {
            KeyMode.InKey -> StripKey(LickPitch.degreeToMidi(offset, baseMidi, scale), label, offset, black = false)
            KeyMode.Chromatic -> {
                val midi = (baseMidi + offset).coerceIn(0, 127)
                StripKey(midi, label, LickPitch.degreeOf(midi, baseMidi, scale), black = keys.any { it in BLACK })
            }
        }
    }
}

/**
 * Consumes only what Lick Lab plays: keys mapped in [mode] and Shift. Arrows, Esc, Enter and app shortcuts go on.
 * A key up is passed for a key mapped in either mode, so switching modes mid-note never strands it.
 */
internal fun lickKeyEvent(e: KeyEvent, mode: KeyMode, actions: LickLabActions): Boolean {
    val k = e.key
    return when (e.type) {
        KeyEventType.KeyDown -> {
            if (k !in SHIFTS && LickKeyMap.offsetFor(k, mode) == null) return false
            if (e.isMetaPressed || e.isCtrlPressed || e.isAltPressed) return false
            actions.keyDown(k)
            true
        }
        KeyEventType.KeyUp -> {
            if (k !in SHIFTS && KeyMode.entries.none { LickKeyMap.offsetFor(k, it) != null }) return false
            actions.keyUp(k)
            true
        }
        else -> false
    }
}

/**
 * Makes the surface the keyboard's target: focusable, keys per [lickKeyEvent], and [LickLabActions.releaseAll] when
 * focus leaves it or the window. [onFocused] tracks focus for the strip's dimming.
 */
@Composable
internal fun Modifier.lickLabKeys(
    requester: FocusRequester,
    mode: KeyMode,
    actions: LickLabActions,
    onFocused: (Boolean) -> Unit,
): Modifier {
    val currentMode by rememberUpdatedState(mode)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, actions) { if (!windowFocused) actions.releaseAll() }
    val had = remember { booleanArrayOf(false) }
    return this
        .focusRequester(requester)
        .onFocusChanged {
            if (had[0] && !it.hasFocus) actions.releaseAll()
            had[0] = it.hasFocus
            onFocused(it.hasFocus)
        }
        .onPreviewKeyEvent { lickKeyEvent(it, currentMode, actions) }
        .focusable()
}

/**
 * The clickable keyboard: Mac key, note name and degree on each key, lit while [heldMidi] sounds, chord tones
 * tinted. Click plays (lower on the key is harder), a sideways drag slides with the drag's speed setting the glide,
 * and a vertical drag bends.
 */
@Composable
internal fun KeyStrip(
    keys: List<StripKey>,
    heldMidi: Int?,
    isChordTone: (StripKey) -> Boolean,
    focused: Boolean,
    actions: LickLabActions,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    hint: KeyHint? = null,
    beatNow: () -> Double = { 0.0 },
    msPerBeat: () -> Double = { 500.0 },
    frame: State<Long>? = null,
) {
    val latestKeys by rememberUpdatedState(keys)
    val latestOnPress by rememberUpdatedState(onPress)
    val deadZone = with(LocalDensity.current) { BEND_DEAD_ZONE.toPx() }
    val flash = remember { HintFlash() }
    val lap = remember { ZipLap() }
    Row(
        modifier
            .testTag(KEY_STRIP_TAG)
            .graphicsLayer { alpha = if (focused) 1f else 0.45f }
            .keyHints(keys.size, hint, beatNow, msPerBeat, frame, flash, lap)
            .pointerInput(actions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    latestOnPress()
                    val strip = latestKeys
                    if (strip.isEmpty()) return@awaitEachGesture
                    fun keyAt(x: Float) = (x / size.width * strip.size).toInt().coerceIn(0, strip.lastIndex)
                    val id = down.id.value
                    var index = keyAt(down.position.x)
                    val velocity = (0.35f + 0.65f * down.position.y / size.height).coerceIn(0.1f, 1f)
                    actions.pointerDown(id, strip[index].midi, velocity)
                    var lastChange = down.uptimeMillis
                    var lastBend = 0f
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val i = keyAt(change.position.x)
                            if (i != index) {
                                val msPerKey = (change.uptimeMillis - lastChange).toFloat() / abs(i - index)
                                actions.pointerSlide(id, strip[i].midi, glideFor(msPerKey))
                                index = i
                                lastChange = change.uptimeMillis
                            }
                            val dy = down.position.y - change.position.y
                            val past = (abs(dy) - deadZone).coerceAtLeast(0f) * sign(dy)
                            val bend = (past / size.height * BEND_PER_KEY).coerceIn(-BEND_PER_KEY, BEND_PER_KEY)
                            if (abs(bend - lastBend) > 0.01f) {
                                actions.pointerBend(id, bend)
                                lastBend = bend
                            }
                        }
                    } finally {
                        actions.pointerUp(id)
                    }
                }
            },
    ) {
        keys.forEachIndexed { i, key ->
            StripKeyCell(key, lit = key.midi == heldMidi, chordTone = isChordTone(key), Modifier.weight(1f).testTag(stripKeyTag(i)))
        }
    }
}

/**
 * The next chord on the keys: [tones] (key indices) and [preferred] light over the [windowBeats] before [changeBeat],
 * an absolute [LickClock] beat.
 */
@Immutable
internal data class KeyHint(val changeBeat: Double, val windowBeats: Double, val tones: Set<Int>, val preferred: Int?)

/** The hint for [keys] while the next chord is known, else null. */
internal fun keyHint(ui: LickLabUiState, keys: List<StripKey>): KeyHint? {
    val change = ui.changeBeat ?: return null
    val next = ui.nextChordRootDegree
    val n = ui.scale.size
    if (next < 0 || n == 0 || ui.hintWindowBeats <= 0.0) return null
    val tones = keys.indices.filterTo(mutableSetOf()) { i -> keys[i].degree?.let { (it - next).mod(n) in CHORD_STEPS } == true }
    val preferred = LickHint.pick(ui.hintMode, keys, ui.chordRootDegree, next, n, ui.handMidi)
    return KeyHint(change, ui.hintWindowBeats, tones, preferred)
}

internal val HINT_INK = OrpheusColors.krakenTeal
private val ZIP_GLOW = OrpheusColors.engineYellow
private val ZIP_CORE = lerp(OrpheusColors.engineYellow, Color.White, 0.6f)
private const val FLASH_MS = 350f

/** The zip band's half width as a share of the key's border, its peak alpha, and the nested runs that taper it. */
private const val ZIP_HALF = 0.045f
private const val ZIP_PEAK = 0.55f
private const val ZIP_LAYERS = 16

/** The lap's opening share it takes to fade in, like the transport zip's ends. */
private const val ZIP_FADE_IN = 0.15f

/** A change estimate within this of a running lap's is the same change, matching the view model's re-anchor. */
private const val LAP_NUDGE_BEATS = 0.25

private fun easeInOut(u: Float): Float = u * u * (3f - 2f * u)

/** Each of [layers] nested runs' alpha, so where all of them overlap the band reaches [peak]. */
internal fun zipLayerAlpha(peak: Float, layers: Int): Float = 1f - (1f - peak).pow(1f / layers)

/**
 * One zip lap timed on frame time, fixed when its window opens, so beat-clock nudges never jerk it. A window
 * opened late keeps the share already gone, so the lap still ends on the change.
 */
internal class ZipLap {
    private var change = Double.NaN
    private var startMs = 0L
    private var endMs = 0L

    /** The lap's progress 0..1 at frame time [ms], beat [now], or null outside [hint]'s window. */
    fun progress(hint: KeyHint, now: Double, ms: Long, msPerBeat: Double): Float? {
        // A running lap rides out the small nudges the change estimate gets, instead of restarting backwards.
        val nudged = ms < endMs && abs(hint.changeBeat - change) <= LAP_NUDGE_BEATS
        if (hint.changeBeat != change && !nudged) {
            if (now < hint.changeBeat - hint.windowBeats || now >= hint.changeBeat) return null
            change = hint.changeBeat
            endMs = ms + ((hint.changeBeat - now) * msPerBeat).toLong()
            startMs = endMs - (hint.windowBeats * msPerBeat).toLong().coerceAtLeast(1L)
        }
        if (ms >= endMs) return null
        return ((ms - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

/** Draw-phase memory of the change the zip last ran toward, so the flash fires once the clock passes it. */
internal class HintFlash {
    var pendingBeat = 0.0
    var pendingKey = -1
    var key = -1
    var startMs = 0L

    /**
     * Moves to beat [now] and frame time [ms], null while the frame clock is stopped (which ends any flash), and
     * returns the flash's progress 0..1 on [key], or null with none showing.
     */
    fun step(hint: KeyHint?, now: Double, ms: Long?, count: Int): Float? {
        if (ms == null) {
            pendingKey = -1
            key = -1
            return null
        }
        // A change the clock has passed fires before the next hint can replace it.
        if (pendingKey >= 0 && now >= pendingBeat) {
            // A change passed long ago (the panel was idle) never flashes late.
            if (now - pendingBeat < 0.5) {
                key = pendingKey
                startMs = ms
            }
            pendingKey = -1
        }
        if (hint == null) {
            // Retracted before its change: that change may not land.
            pendingKey = -1
        } else if (hint.preferred != null && now >= hint.changeBeat - hint.windowBeats && now < hint.changeBeat) {
            pendingBeat = hint.changeBeat
            pendingKey = hint.preferred
        }
        if (key < 0) return null
        val t = (ms - startMs) / FLASH_MS
        if (t !in 0f..1f || key >= count) {
            key = -1
            return null
        }
        return t
    }
}

/**
 * Teal inset borders on the next chord's keys, rising over the window; one zip lap around the preferred key from
 * top centre, back there on the change; then a soft gold flash. Reads the clock only while drawing.
 */
private fun Modifier.keyHints(
    count: Int,
    hint: KeyHint?,
    beatNow: () -> Double,
    msPerBeat: () -> Double,
    frame: State<Long>?,
    flash: HintFlash,
    lap: ZipLap,
): Modifier =
    if (count == 0) this else drawWithCache {
        val keyW = size.width / count
        val h = size.height
        val line = 2.5.dp.toPx()
        // One border for the teal and the zip, so the zip rides the teal line instead of running beside it.
        val border = keyBorder(keyW, h, line / 2 + 1.dp.toPx(), 4.dp.toPx())
        val measure = PathMeasure().apply { setPath(border, true) }
        val length = measure.length
        val zip = Path()
        val tealStroke = Stroke(1.5.dp.toPx())
        val glow = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round)
        val core = Stroke(line, cap = StrokeCap.Round)
        val ring = 2.dp.toPx()
        val ringStroke = Stroke(ring)
        val corner = 4.dp.toPx()
        onDrawWithContent {
            drawContent()
            val ms = frame?.value
            val now = beatNow()
            val t = flash.step(hint, now, ms, count)
            if (hint != null) {
                val start = hint.changeBeat - hint.windowBeats
                if (now >= start && now < hint.changeBeat) {
                    val p = ((now - start) / hint.windowBeats).toFloat().coerceIn(0f, 1f)
                    val teal = HINT_INK.copy(alpha = 0.3f + 0.7f * p)
                    for (i in hint.tones) translate(left = i * keyW) { drawPath(border, teal, style = tealStroke) }
                    val u = if (ms == null) null else lap.progress(hint, now, ms, msPerBeat())
                    if (hint.preferred != null && u != null) {
                        // One eased lap from top centre, back there on the change. Nested continuous runs, shortest
                        // last, stack into a band brightest at its centre with no seams between pieces.
                        val centre = easeInOut(u) * length
                        val half = ZIP_HALF * length
                        val peak = ZIP_PEAK * (u / ZIP_FADE_IN).coerceAtMost(1f)
                        val a = zipLayerAlpha(peak, ZIP_LAYERS)
                        translate(left = hint.preferred * keyW) {
                            zip.reset()
                            segment(measure, length, centre - half, centre + half, zip)
                            drawPath(zip, ZIP_GLOW.copy(alpha = peak * 0.12f), style = glow)
                            for (k in ZIP_LAYERS downTo 1) {
                                val w = half * k / ZIP_LAYERS
                                zip.reset()
                                segment(measure, length, centre - w, centre + w, zip)
                                drawPath(zip, ZIP_CORE.copy(alpha = a), style = core)
                            }
                        }
                    }
                }
            }
            if (t != null) {
                val e = (1f - t) * (1f - t)
                val x = flash.key * keyW
                drawRect(ZIP_GLOW.copy(alpha = 0.25f * e), Offset(x, 0f), Size(keyW, h))
                for (r in 1..3) {
                    val d = r * ring
                    drawRoundRect(
                        ZIP_GLOW.copy(alpha = 0.6f * e / r),
                        Offset(x - d, -d),
                        Size(keyW + 2 * d, h + 2 * d),
                        CornerRadius(corner + d),
                        style = ringStroke,
                    )
                }
            }
        }
    }

/** A rounded rect [inset] into a [w] by [h] key, starting at top centre and running clockwise. */
private fun keyBorder(w: Float, h: Float, inset: Float, radius: Float): Path {
    val l = inset
    val t = inset
    val r = w - inset
    val b = h - inset
    val c = radius.coerceAtMost(min(r - l, b - t) / 2)
    return Path().apply {
        moveTo(w / 2, t)
        lineTo(r - c, t)
        arcTo(Rect(r - 2 * c, t, r, t + 2 * c), -90f, 90f, false)
        lineTo(r, b - c)
        arcTo(Rect(r - 2 * c, b - 2 * c, r, b), 0f, 90f, false)
        lineTo(l + c, b)
        arcTo(Rect(l, b - 2 * c, l + 2 * c, b), 90f, 90f, false)
        lineTo(l, t + c)
        arcTo(Rect(l, t, l + 2 * c, t + 2 * c), 180f, 90f, false)
        close()
    }
}

/**
 * The border from [from] to [to] (either may run past an end of the lap) into [out]. A run across the lap's start
 * continues as one path, so no cap lands on the seam.
 */
private fun segment(measure: PathMeasure, length: Float, from: Float, to: Float, out: Path) {
    when {
        from < 0f -> {
            measure.getSegment(length + from, length, out, true)
            measure.getSegment(0f, to, out, false)
        }
        to > length -> {
            measure.getSegment(from, length, out, true)
            measure.getSegment(0f, to - length, out, false)
        }
        else -> measure.getSegment(from, to, out, true)
    }
}

/** A quick drag slides fast, a slow one glides slowly: 50 ms a key is 0.08, 300 ms is 0.5. */
internal fun glideFor(msPerKey: Float): Float = (msPerKey / 600f).coerceIn(0.05f, 0.6f)

@Composable
private fun StripKeyCell(key: StripKey, lit: Boolean, chordTone: Boolean, modifier: Modifier) {
    val fill = when {
        lit -> MixerInk.changed
        key.black -> MixerInk.sourceCard
        chordTone -> MixerInk.accent.copy(alpha = 0.3f)
        else -> MixerInk.card
    }
    val ink = if (lit) MixerInk.onBright else MixerInk.text
    Column(
        modifier
            .fillMaxHeight()
            .background(fill)
            .border(0.5.dp, if (key.degree == 0) MixerInk.accentSoft else MixerInk.line)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key.macKeys, style = mixText(legible(LegibleText.MIXER_MIN), ink, FontWeight.Bold), maxLines = 1)
        Text(LickPitch.noteName(key.midi), style = mixText(legible(LegibleText.MIXER_MIN), ink), maxLines = 1)
        Text(
            key.degree?.toString() ?: "·",
            style = mixText(legible(LegibleText.MIXER_MIN), if (lit) ink else MixerInk.dim),
            maxLines = 1,
        )
    }
}

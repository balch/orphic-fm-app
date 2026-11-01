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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.abs
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
) {
    val latestKeys by rememberUpdatedState(keys)
    val latestOnPress by rememberUpdatedState(onPress)
    val deadZone = with(LocalDensity.current) { BEND_DEAD_ZONE.toPx() }
    Row(
        modifier
            .testTag(KEY_STRIP_TAG)
            .graphicsLayer { alpha = if (focused) 1f else 0.45f }
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

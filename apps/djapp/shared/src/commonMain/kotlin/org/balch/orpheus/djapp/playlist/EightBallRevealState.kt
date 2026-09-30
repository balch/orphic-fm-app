package org.balch.orpheus.djapp.playlist

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlin.random.Random

/**
 * Where the reveal is. [Showing.from] is the dome's bounds in root coordinates: the ball rises from
 * there and sinks back. [Open] is the playlist sheet open, with the ball resting in the dome's ring,
 * and [Closing] the sheet closing, the ball turning back into the dome.
 */
internal sealed interface RevealPhase {
    data object Idle : RevealPhase
    data class Showing(val from: Rect) : RevealPhase
    data class Sinking(val from: Rect) : RevealPhase
    data object Open : RevealPhase
    data object Closing : RevealPhase
}

/** How far through its flight the phrase begins to hand over to the header's own copy. */
private const val LandingStart = 0.8f

/**
 * A face turning about its vertical axis at [progress] (0 to 1): edge-on at 90° by the midpoint, then
 * in from -90° to 0° as what stands behind it. Read in draw, so it allocates nothing.
 */
internal fun turnDegrees(progress: Float): Float = if (progress < 0.5f) progress * 180f else (progress - 1f) * 180f

/**
 * The 8-ball reveal DjAppScreen runs over its chrome, handed to the dome through [LocalEightBall].
 * A long press [launch]es it; `EightBallReveal` plays it out and [handOver]s to the playlist sheet,
 * and the ball stays in the ring while the sheet is open. However the sheet then closes, the ball
 * turns back into the dome: a tap on the ring [close]s it, and the scrim and Back arrive as [sheetOpen].
 *
 * Everything here is written on the main thread: `EightBallReveal`'s frame-clock coroutine writes
 * [flip], the header's layout callback writes the phrase's anchor, and DjAppScreen writes [sheetOpen].
 * Draw reads them through their state, so a frame of any motion recomposes nothing.
 */
@Stable
internal class EightBallRevealState(
    private val openSheet: () -> Unit,
    private val closeSheet: () -> Unit,
    private val random: Random = Random,
) {
    var phase: RevealPhase by mutableStateOf(RevealPhase.Idle)
        private set

    /** The stage's bounds in root coordinates: the ball rises to its centre. */
    var stageBounds: Rect by mutableStateOf(Rect.Zero)

    /** The phrase last shown. The sheet's header shows it, and the next one always differs. */
    var phrase: String by mutableStateOf(nextPhrase(null, random = random))
        private set

    /**
     * Whether the playlist sheet is open, kept in step by DjAppScreen. It closing (the scrim, Back,
     * another sheet) turns the ball back into the dome; it opening with no reveal (a restored sheet)
     * puts the ball in the ring.
     */
    var sheetOpen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            when {
                !value && phase == RevealPhase.Open -> turnBack()
                value && phase == RevealPhase.Idle -> phase = RevealPhase.Open
            }
        }

    /** How far the ball has risen from the dome to the stage's centre, 0 to 1 (a spring, so a touch past it). Written by `EightBallReveal`. */
    var travel: Float by mutableFloatStateOf(0f)

    /** The turn back's progress, 0 the ball and 1 the dome, written by `EightBallReveal` while [RevealPhase.Closing]. */
    var flip: Float by mutableFloatStateOf(0f)

    /**
     * What the ring shows, read in draw: 0 is the ball, 1 the dome, and between them the turn of
     * [turnDegrees]. The dome hides while the ball stands in for it, on the stage or in the ring.
     */
    val ringTurn: Float
        get() = when (phase) {
            RevealPhase.Idle -> 1f
            RevealPhase.Closing -> flip
            else -> 0f
        }

    /** The vibe's name pill over the ring is composed: not once the sheet is opening, nor while it is open. */
    val namePresent: Boolean get() = phase != RevealPhase.Open && phase !is RevealPhase.Sinking

    /**
     * How much of that pill shows, read in draw. It fades away as the ball rises, so the title is not
     * left hanging over the ball's path, and fades back in as the dome turns in.
     */
    val nameAlpha: Float
        get() = when (phase) {
            RevealPhase.Idle -> 1f
            is RevealPhase.Showing -> 1f - travel.coerceIn(0f, 1f)
            RevealPhase.Closing -> flip
            else -> 0f
        }

    /** The playlist is open and the ball rests in the ring: the dome's labels are the ball's. */
    val isOpen: Boolean get() = phase == RevealPhase.Open

    /** The ring draws the ball itself once the reveal's own ball has sunk into it, until it has turned back. */
    val showsBallInRing: Boolean get() = phase == RevealPhase.Open || phase == RevealPhase.Closing

    /** Where the sheet's phrase line sits, root coordinates: its left edge and vertical centre. NaN until laid out. */
    var phraseX: Float by mutableFloatStateOf(Float.NaN)
        private set
    var phraseY: Float by mutableFloatStateOf(Float.NaN)
        private set

    /** Written by the header's layout callback, each frame of the sheet's slide, for the phrase to fly to. */
    fun anchorPhrase(x: Float, y: Float) {
        phraseX = x
        phraseY = y
    }

    /** The phrase's flight to the header: 0 hovering above the ball, 1 on the header's line. Written by `EightBallReveal`. */
    var flight: Float by mutableFloatStateOf(0f)

    /**
     * How much of the header's own phrase shows, read in draw. The reveal's phrase stands in for it
     * until it has flown there; over the last of the flight the two crossfade, so the header's, which its
     * slot may clip, takes over without a pop.
     */
    val headerPhraseAlpha: Float
        get() = when (phase) {
            is RevealPhase.Showing -> 0f
            is RevealPhase.Sinking -> ((flight - LandingStart) / (1f - LandingStart)).coerceIn(0f, 1f)
            else -> 1f
        }

    fun launch(from: Rect) {
        if (phase != RevealPhase.Idle) return
        phrase = nextPhrase(phrase, random = random)
        travel = 0f
        phase = RevealPhase.Showing(from)
    }

    /** Opens the sheet with no reveal: the dome's "Playlist" accessibility action. */
    fun openNow() {
        openSheet()
        phase = RevealPhase.Open
    }

    /** A new phrase for the sheet's Shuffle shake. */
    fun roll(): String {
        phrase = nextPhrase(phrase, random = random)
        return phrase
    }

    /** The reveal is done, or a tap skipped it: the sheet opens and the ball sinks back into the dome. */
    fun handOver() {
        val showing = phase as? RevealPhase.Showing ?: return
        openSheet()
        phase = RevealPhase.Sinking(showing.from)
    }

    /**
     * The ball has sunk and the phrase has landed, and it rests in the ring if the sheet is still
     * open; or the ball has turned back, and the dome is home.
     */
    fun finish() {
        if (phase !is RevealPhase.Sinking && phase != RevealPhase.Closing) return
        phase = if (sheetOpen) RevealPhase.Open else RevealPhase.Idle
    }

    /** The ring's ball was tapped: the playlist closes and the ball turns back into the dome. */
    fun close() {
        if (phase != RevealPhase.Open) return
        closeSheet()
        // The sheet's echo through sheetOpen may already have begun the turn.
        if (phase == RevealPhase.Open) turnBack()
    }

    private fun turnBack() {
        flip = 0f
        phase = RevealPhase.Closing
    }
}

/** DjAppScreen provides it; TV hardware, previews and tests without it get no long press. */
internal val LocalEightBall = staticCompositionLocalOf<EightBallRevealState?> { null }

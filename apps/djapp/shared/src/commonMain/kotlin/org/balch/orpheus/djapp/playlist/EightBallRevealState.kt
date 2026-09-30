package org.balch.orpheus.djapp.playlist

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlin.random.Random

/**
 * Where the reveal is. [Showing.from] is the dome's bounds in root coordinates: the ball rises from
 * there and sinks back. [Open] is the playlist sheet open, with the ball resting in the dome's ring.
 */
internal sealed interface RevealPhase {
    data object Idle : RevealPhase
    data class Showing(val from: Rect, val phrase: String) : RevealPhase
    data class Sinking(val from: Rect) : RevealPhase
    data object Open : RevealPhase
}

/**
 * The 8-ball reveal DjAppScreen runs over its chrome, handed to the dome through [LocalEightBall].
 * A long press [launch]es it; `EightBallReveal` plays it out and [handOver]s to the playlist sheet,
 * and the ball stays in the ring while the sheet is open. A tap there [close]s it.
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
     * Whether the playlist sheet is open, kept in step by DjAppScreen. It closing (scrim, Back, another
     * sheet) puts the dome back; it opening with no reveal (a restored sheet) puts the ball in the ring.
     */
    var sheetOpen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            when {
                !value && phase == RevealPhase.Open -> phase = RevealPhase.Idle
                value && phase == RevealPhase.Idle -> phase = RevealPhase.Open
            }
        }

    /** The dome hides while the ball stands in for it. */
    val hidesDome: Boolean get() = phase != RevealPhase.Idle

    /** The ring draws the ball itself only once the reveal's own ball has sunk into it. */
    val showsBallInRing: Boolean get() = phase == RevealPhase.Open

    fun launch(from: Rect) {
        if (phase != RevealPhase.Idle) return
        phrase = nextPhrase(phrase, random = random)
        phase = RevealPhase.Showing(from, phrase)
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

    /** The ball has sunk: it rests in the ring if the sheet is still open. */
    fun finish() {
        if (phase !is RevealPhase.Sinking) return
        phase = if (sheetOpen) RevealPhase.Open else RevealPhase.Idle
    }

    /** The ring's ball was tapped: the playlist closes and the dome returns. */
    fun close() {
        if (phase != RevealPhase.Open) return
        closeSheet()
        phase = RevealPhase.Idle
    }
}

/** DjAppScreen provides it; TV hardware, previews and tests without it get no long press. */
internal val LocalEightBall = staticCompositionLocalOf<EightBallRevealState?> { null }

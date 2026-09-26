package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.pulsar.playback.songArc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * One tracker update: whose song, where it is and how long it runs, in ms. Until [final] the
 * length is only the tracker's estimate, and the song's last section has not begun. [estimateMs]
 * is the song's first estimate, which holds while the length moves on to the forced or rolling end.
 */
@Immutable
internal data class SongPosition(
    val song: String,
    val positionMs: Long,
    val durationMs: Long,
    val final: Boolean = true,
    val estimateMs: Long = durationMs,
    /** The song has no end left to reach: the gap past the arc lights up (see EndlessGap). */
    val endless: Boolean = false,
    /** Where the end locked in, which the arc bends from (see songArc); null until then. */
    val lockedAtMs: Long? = null,
)

/** The nav state's song times, or null without them (no bar yet, or a render harness). */
internal fun VibeNavState.songPosition(): SongPosition? {
    val position = positionMs ?: return null
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    return SongPosition(currentName, position, duration, durationFinal, estimateMs?.takeIf { it > 0L } ?: duration, endless, lockedAtMs)
}

/** The loop-cycle a hold or an ease may cover until two updates have measured one. */
private const val DefaultCycleMs = 8_000L

/** An update ahead of the display closes most of the gap in about this long: quick, but never a jump. */
private const val CatchUpMillis = 150f

/** Behind the display and this close to the start: the song started over. */
private const val RestartWithinMs = 1_000L

/**
 * The song position to draw between the tracker's once-a-loop-cycle updates, on a clock that runs
 * only while playing. It runs on at play time from the last update, at most a cycle past it, eases
 * up to one ahead of it, and holds for one less than a cycle behind until the song catches up,
 * never backing up. A new song, a restart, or an update a cycle or more away (a seek) jumps.
 *
 * Its fraction is [songArc] at that position, from the latest update's numbers alone, so a ring
 * mounted late or remounted draws the arc every other surface does.
 */
@Immutable
internal class SmoothPosition private constructor(
    private val update: SongPosition,
    private val anchorMs: Long,
    private val shownMs: Float,
    private val realMs: Float,
    private val cycleMs: Long,
) {
    val song: String get() = update.song
    val durationMs: Long get() = update.durationMs
    val endless: Boolean get() = update.endless

    fun positionAt(clockMs: Long): Float {
        val dt = (clockMs - anchorMs).coerceAtLeast(0L).toFloat()
        // Capped a cycle on: if the updates stall, the next one never has to pull it back.
        val real = realMs + min(dt, cycleMs.toFloat())
        val behind = realMs - shownMs
        val shown = if (behind > 0f) real - behind * exp(-dt / CatchUpMillis) else max(shownMs, real)
        return shown.coerceAtMost(update.durationMs.toFloat())
    }

    fun fractionAt(clockMs: Long): Float =
        songArc(positionAt(clockMs), update.durationMs, update.final, update.estimateMs, update.lockedAtMs)

    fun next(update: SongPosition, clockMs: Long): SmoothPosition {
        // Same song, same boundary, new length (the ending armed mid-cycle): the song has not moved,
        // so re-anchoring that stale boundary to now would stall the display until the next one.
        if (update.song == song && update.positionMs == realMs.toLong()) {
            return SmoothPosition(update, anchorMs, shownMs, realMs, cycleMs)
        }
        val shown = positionAt(clockMs)
        val real = update.positionMs.toFloat()
        val jump = update.song != song ||
            (real < shown && update.positionMs <= RestartWithinMs) ||
            abs(real - shown) >= cycleMs
        // A cycle is the step between two updates that ran on from each other; a jump measures nothing.
        val step = update.positionMs - realMs.toLong()
        val cycle = if (!jump && step > 0L) step else cycleMs
        return SmoothPosition(update, clockMs, if (jump) real else shown, real, cycle)
    }

    companion object {
        fun start(update: SongPosition, clockMs: Long): SmoothPosition {
            val real = update.positionMs.toFloat()
            return SmoothPosition(update, clockMs, real, real, DefaultCycleMs)
        }
    }
}

/** The progress to draw now, on a [ProgressWave]'s play clock. Read only in draw. */
@Stable
internal class SmoothProgress(private val state: State<SmoothPosition?>, private val wave: ProgressWave) {
    /**
     * The fraction to draw where the tracker says [coarse], for a draw that has already checked for
     * null: a plain Float, never boxed. Without song times it is [coarse] itself.
     */
    fun fractionOr(coarse: Float): Float {
        val smooth = state.value ?: return coarse
        return smooth.fractionAt(wave.playMs)
    }

    /** Whether the song plays on with no end left to reach; false without song times (TV, render harness). */
    val endless: Boolean get() = state.value?.endless == true

    /** How far into the song that is, in ms, or [fallback] without song times. */
    fun positionMsOr(fallback: Long): Long {
        val smooth = state.value ?: return fallback
        return smooth.positionAt(wave.playMs).toLong()
    }
}

/**
 * Runs the playhead on between the tracker's updates (see [SmoothPosition]) on [wave]'s play
 * clock, which advances only in the wave's frame loop: no frames of its own, and it stands still
 * through a pause. [position] is read only in a snapshot collector, never in composition. TV
 * hardware and the render harness run no clock, so they draw the tracker's own fraction.
 */
@Composable
internal fun rememberSmoothProgress(wave: ProgressWave, position: () -> SongPosition?): SmoothProgress {
    val state = remember { mutableStateOf<SmoothPosition?>(null) }
    val currentPosition by rememberUpdatedState(position)
    if (wave.clocked) {
        LaunchedEffect(wave) {
            snapshotFlow { currentPosition() }.collect { update ->
                val clock = wave.playMs
                state.value = update?.let { state.value?.next(it, clock) ?: SmoothPosition.start(it, clock) }
            }
        }
    }
    return remember(wave) { SmoothProgress(state, wave) }
}

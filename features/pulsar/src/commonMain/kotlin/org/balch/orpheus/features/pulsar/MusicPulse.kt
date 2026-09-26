package org.balch.orpheus.features.pulsar

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.plugin.viz.PULSAR_MAX_STEPS
import org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.math.exp

/** 16 steps a bar, 4 a beat. */
private const val StepsPerBeat = 4

/** Step-change timestamps the tempo is measured over; a window of 8 steps averages out the poll's jitter. */
private const val TempoWindow = 9

/** A gap between steps longer than this is a pause (or under 15 BPM), not a step. */
private const val MaxStepMs = 1_000L

/** No sample for this many beats (or [MaxStepMs] before the tempo is known): the viz stopped, hidden or paused. */
private const val GapBeats = 3

/** Sum of track peaks that reads as about 0.63; a full mix lands near 1, one quiet pad near 0.15. */
private const val LoudnessKnee = 1f

/**
 * The music right now, one per Pulsar viz sample: [level] is the whole mix 0..1, [trackLevels]
 * each track's peak, [beatPhase] 0 on a beat rising to 1 at the next, and [msPerBeat] the
 * measured tempo (0 until two steps have been seen).
 */
@Immutable
class MusicPulse(
    val level: Float,
    val trackLevels: FloatArray,
    val beatPhase: Float,
    val msPerBeat: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is MusicPulse && level == other.level && beatPhase == other.beatPhase &&
            msPerBeat == other.msPerBeat && trackLevels.contentEquals(other.trackLevels)

    override fun hashCode(): Int =
        ((level.hashCode() * 31 + beatPhase.hashCode()) * 31 + msPerBeat.hashCode()) * 31 + trackLevels.contentHashCode()

    companion object {
        val SILENT = MusicPulse(0f, FloatArray(PULSAR_NUM_TRACKS), 0f, 0f)
    }
}

/**
 * One beat of the song: its loudest moment, how much each track sounded across it (colour-agnostic),
 * and where it sat in the song, [positionMs] from the start (-1 when unknown) for [durationMs].
 */
@Immutable
class StoryBeat(val level: Float, val trackEnergy: FloatArray, val positionMs: Long, val durationMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is StoryBeat && level == other.level && positionMs == other.positionMs &&
            durationMs == other.durationMs && trackEnergy.contentEquals(other.trackEnergy)

    override fun hashCode(): Int =
        ((level.hashCode() * 31 + positionMs.hashCode()) * 31 + durationMs.hashCode()) * 31 + trackEnergy.contentHashCode()
}

/**
 * The current song so far, laid out against [elapsedMs], the song position the seek bar shows. A
 * stretch the app did not watch has no beats and draws flat. Starts empty on every new song.
 */
@Immutable
data class SongStory(val beats: List<StoryBeat>, val elapsedMs: Long = 0L) {
    companion object {
        val EMPTY = SongStory(emptyList())

        /** About 34 minutes at 120 BPM; later beats are dropped, so the tail past it draws flat. */
        const val MAX_BEATS = 4096
    }
}

/** The track peaks summed and soft-clipped to 0..1, so one quiet pad reads low and a full mix near 1. */
internal fun musicLoudness(trackLevels: FloatArray): Float {
    var sum = 0f
    for (level in trackLevels) sum += level.coerceAtLeast(0f)
    return 1f - exp(-sum / LoudnessKnee)
}

/**
 * The track whose playhead times the beats: the most steps, lowest index on a tie. Steps are
 * exported clamped to the viz window, where a longer track's playhead parks at the last step, so
 * a track below the window wins over one at it. -1 when no track is playing. When every track sits
 * at the window the lowest index wins, and a true 64-step track there stalls the beats for half its loop.
 */
internal fun beatReferenceTrack(playheads: IntArray, stepCounts: IntArray): Int {
    var best = -1
    var bestSteps = 0
    var bestBelowWindow = false
    for (t in playheads.indices) {
        val steps = stepCounts.getOrElse(t) { 0 }
        if (playheads[t] < 0 || steps <= 0) continue
        val belowWindow = steps < PULSAR_MAX_STEPS
        val better = best < 0 || (belowWindow && !bestBelowWindow) ||
            (belowWindow == bestBelowWindow && steps > bestSteps)
        if (better) {
            best = t
            bestSteps = steps
            bestBelowWindow = belowWindow
        }
    }
    return best
}

/**
 * Where in the beat [step] sits, [msSinceStep] after it began: 0 on a beat, rising smoothly to 1
 * at the next. An overdue step holds at its end until the playhead moves; an unmeasured tempo
 * ([msPerStep] 0) holds at the step's start.
 */
internal fun beatPhase(step: Int, msSinceStep: Float, msPerStep: Float): Float {
    val within = if (msPerStep > 0f) (msSinceStep / msPerStep).coerceIn(0f, 1f) else 0f
    return (step.mod(StepsPerBeat) + within) / StepsPerBeat
}

/**
 * Turns Pulsar viz samples into beats: a beat closes when the reference track's playhead crosses
 * a 4-step boundary or wraps, carrying its loudest moment, each track's summed level and its place
 * in the song. Held playheads close nothing. Call from one coroutine.
 *
 * The song position ([onProgress]) ticks a loop-cycle at a time, so beats are placed from its last
 * tick plus the steps counted since, which also holds still through a pause. When the position moved
 * on with no step seen, the song played unwatched (the app hidden), so the clock places them instead.
 */
internal class BeatAccumulator {
    private val lastPlayheads = IntArray(PULSAR_NUM_TRACKS) { -1 }

    // The last [TempoWindow] step times, oldest at [stepHead]: a ring of plain Longs, never boxed.
    private val stepTimes = LongArray(TempoWindow)
    private var stepHead = 0
    private var stepCount = 0
    private var lastStepMs = 0L
    private var lastSampleMs = -1L
    private var measuredMsPerStep = 0f

    /** Steps seen this song: the song clock while the viz is watched. */
    private var stepClock = 0L
    private var anchorPositionMs = -1L
    private var anchorStep = 0L
    private var anchorAtMs = 0L
    private var unwatched = false

    private var beatLevel = 0f
    private val beatEnergy = FloatArray(PULSAR_NUM_TRACKS)
    private var beatSamples = 0
    private var beatStartStep = 0L
    private var beatStartMs = 0L
    private var beatLastStep = 0L

    /** Placed when the beat opens; -1 until the song position is known, then placed at the close. */
    private var beatPositionMs = -1L

    /** The latest sample as a [MusicPulse]. */
    var pulse: MusicPulse = MusicPulse.SILENT
        private set

    /** A new song: the pending beat, the tempo and the song clock start over. */
    fun reset() {
        lastPlayheads.fill(-1)
        clearSteps()
        lastStepMs = 0L
        lastSampleMs = -1L
        measuredMsPerStep = 0f
        stepClock = 0L
        anchorPositionMs = -1L
        unwatched = false
        clearBeat()
        pulse = MusicPulse.SILENT
    }

    /** The seek bar's song position at [nowMs], or null when the song has none. */
    fun onProgress(positionMs: Long?, nowMs: Long) {
        if (positionMs == null) {
            anchorPositionMs = -1L
            unwatched = false
            return
        }
        if (positionMs == anchorPositionMs) return
        unwatched = anchorPositionMs >= 0 && positionMs > anchorPositionMs && stepClock == anchorStep
        anchorPositionMs = positionMs
        anchorStep = stepClock
        anchorAtMs = nowMs
    }

    /**
     * Feeds one sample taken at [nowMs]; returns the beat it closed, if any. With [buildPulse] false
     * (nothing draws a live wave) [pulse] is left as it was.
     */
    fun add(viz: PulsarVizData, nowMs: Long, buildPulse: Boolean = true): StoryBeat? {
        val loudness = musicLoudness(viz.trackLevels)
        val ref = beatReferenceTrack(viz.playheads, viz.stepCounts)
        val gap = lastSampleMs >= 0 && nowMs - lastSampleMs > gapMs()
        lastSampleMs = nowMs
        var closed: StoryBeat? = null
        var step = 0
        if (ref >= 0) {
            step = viz.playheads[ref]
            val previous = lastPlayheads[ref]
            when {
                previous < 0 -> lastStepMs = nowMs
                gap -> {
                    // The viz stopped: the pending beat ends where it stopped, never across the gap.
                    // Steps missed within a loop are counted but not timed.
                    closed = closeBeat()
                    if (step != previous) {
                        val loop = viz.stepCounts.getOrElse(ref) { 0 }.coerceAtLeast(1)
                        stepClock += (step - previous).mod(loop).coerceAtLeast(1)
                    }
                    clearSteps()
                    lastStepMs = nowMs
                }
                step != previous -> {
                    recordStep(nowMs)
                    // A loop of 4 steps or fewer never changes step / 4; its wrap is the beat.
                    if (step < previous || step / StepsPerBeat != previous / StepsPerBeat) closed = closeBeat()
                }
            }
        } else if (gap) {
            closed = closeBeat()
        }
        viz.playheads.copyInto(lastPlayheads, endIndex = minOf(viz.playheads.size, lastPlayheads.size))
        if (beatSamples == 0) {
            beatStartStep = stepClock
            beatStartMs = nowMs
            beatPositionMs = songPositionAt(stepClock, nowMs)
        }
        beatLastStep = stepClock
        beatLevel = maxOf(beatLevel, loudness)
        for (t in beatEnergy.indices) beatEnergy[t] += viz.trackLevels.getOrElse(t) { 0f }
        beatSamples++
        if (buildPulse) {
            val msPerStep = msPerStep()
            pulse = MusicPulse(
                level = loudness,
                // The viz monitor hands over a fresh array with every sample.
                trackLevels = viz.trackLevels,
                beatPhase = if (ref >= 0) beatPhase(step, (nowMs - lastStepMs).toFloat(), msPerStep) else 0f,
                msPerBeat = msPerStep * StepsPerBeat,
            )
        }
        return closed
    }

    private fun gapMs(): Long = maxOf((GapBeats * StepsPerBeat * msPerStep()).toLong(), MaxStepMs)

    private fun songPositionAt(step: Long, atMs: Long): Long {
        if (anchorPositionMs < 0) return -1L
        val offset = if (unwatched) atMs - anchorAtMs else ((step - anchorStep) * msPerStep()).toLong()
        return (anchorPositionMs + offset).coerceAtLeast(0L)
    }

    private fun recordStep(nowMs: Long) {
        if (nowMs - lastStepMs > MaxStepMs) clearSteps()
        if (stepCount < TempoWindow) {
            stepTimes[(stepHead + stepCount) % TempoWindow] = nowMs
            stepCount++
        } else {
            // Full: the newest takes the oldest's place.
            stepTimes[stepHead] = nowMs
            stepHead = (stepHead + 1) % TempoWindow
        }
        lastStepMs = nowMs
        stepClock++
    }

    private fun clearSteps() {
        stepHead = 0
        stepCount = 0
    }

    // Averaged over the window: the sum of consecutive intervals telescopes to first-to-last. The
    // last measure holds while a fresh window fills after a gap.
    private fun msPerStep(): Float {
        if (stepCount >= 2) {
            val first = stepTimes[stepHead]
            val last = stepTimes[(stepHead + stepCount - 1) % TempoWindow]
            measuredMsPerStep = (last - first).toFloat() / (stepCount - 1)
        }
        return measuredMsPerStep
    }

    private fun closeBeat(): StoryBeat? {
        if (beatSamples == 0) return null
        val position = if (beatPositionMs >= 0) beatPositionMs else songPositionAt(beatStartStep, beatStartMs)
        val duration = ((beatLastStep - beatStartStep + 1) * msPerStep()).toLong()
        val beat = StoryBeat(beatLevel, beatEnergy.copyOf(), position, duration)
        clearBeat()
        return beat
    }

    private fun clearBeat() {
        beatLevel = 0f
        beatEnergy.fill(0f)
        beatSamples = 0
        beatPositionMs = -1L
    }
}

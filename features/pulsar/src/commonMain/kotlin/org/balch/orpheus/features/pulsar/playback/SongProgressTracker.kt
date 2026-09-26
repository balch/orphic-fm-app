package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.models.Arrangement
import kotlin.math.abs

/** How many recent loop-cycle intervals the median is taken over. */
internal const val CycleWindow = 5

/** Samples needed before the measurement overrules the seed; a median of 3 survives one outlier. */
internal const val MinCycleSamples = 3

/**
 * The arrangement state is polled every 200ms, so a boundary's timestamp jitters by up to one
 * poll. A measured cycle within this of the published one is that jitter, not a tempo change.
 */
internal const val CycleJitterToleranceMs = 250f

/** What the tracker needs from the vibe, the current section and the song ending. */
data class SongTiming(
    val vibeName: String,
    val stepCount: Int,
    val bpm: Float,
    val bpmMultiplier: Float,
    /** The authored playing-time range: the ending cannot fire before it and is forced at its end. */
    val songSeconds: IntRange,
    /** The section the song ends in once the ending is armed, else -1. */
    val finalSectionIndex: Int,
    /** Bumped on every apply, so a restart of the same vibe reads as a new song. */
    val songId: Int = 0,
) {
    /**
     * The end to show until the final section is known: three quarters into the authored range.
     * Longer than the typical end, so the bar rarely has to jump forward before the outro.
     */
    val estimatedMs: Long get() = (songSeconds.first + (songSeconds.last - songSeconds.first) * 3 / 4) * 1000L
    val maxMs: Long get() = songSeconds.last * 1000L
}

/**
 * Null for a vibe without sections: the session keeps the last arrangement state, so its section
 * index may still be non-negative, and only the vibe says there is no seek bar.
 */
internal fun songTimingOf(
    vibeName: String,
    stepCount: Int,
    bpm: Float,
    arrangement: Arrangement?,
    sectionIndex: Int,
    finalSectionIndex: Int,
    songId: Int = 0,
): SongTiming? {
    val sections = arrangement?.sections
    if (sections.isNullOrEmpty()) return null
    return SongTiming(
        vibeName = vibeName,
        stepCount = stepCount,
        bpm = bpm,
        bpmMultiplier = sections.getOrNull(sectionIndex)?.bpmMultiplier ?: 1f,
        songSeconds = passSeconds(arrangement, stepCount, bpm) ?: arrangement.lengthSeconds,
        finalSectionIndex = finalSectionIndex,
        songId = songId,
    )
}

/**
 * A play-once song lasts as long as its pass, not its authored range: every section once, each
 * at its own tempo, from the shortest draw of every length to the longest. Null otherwise.
 */
private fun passSeconds(arrangement: Arrangement, stepCount: Int, bpm: Float): IntRange? {
    if (!arrangement.playOnce) return null
    var minMs = 0f
    var maxMs = 0f
    for (section in arrangement.sections) {
        val cycleMs = seedMsPerCycle(stepCount, bpm, section.bpmMultiplier) ?: return null
        minMs += section.barsMin * cycleMs
        maxMs += section.barsMax * cycleMs
    }
    return (minMs / 1000f).toInt()..(maxMs / 1000f).toInt()
}

/**
 * Turns the loop-cycle counter into whole-song seek-bar progress. The position is the bar time
 * accumulated over the sections played, so a pause stalls it. The duration is the authored
 * estimate until the song ending is armed and its final section begins; then it is that
 * section's end, which is when the song really ends. A cycle's length is measured (median of
 * recent intervals), since the loop, BPM knob and elastic tempo all move it. Call from a single
 * collector.
 */
class SongProgressTracker(private val nowMs: () -> Long) {
    private var vibeName: String? = null
    private var songId: Int? = null
    private var vibeStartedMs = 0L
    private var sectionIndex = -1
    private var multiplier = 1f
    private var barsAtBoundary = -1
    private var boundaryMs = 0L
    private val samples = ArrayDeque<Long>()
    private var publishedMsPerCycle: Float? = null

    /** Bar time of the sections already played this song. */
    private var songMsBeforeSection = 0L
    private var sectionBarsTotal = 0
    private var msPerCycle = 0f

    fun update(state: PulsarArrangementState, timing: SongTiming?): PlaybackProgress? {
        if (timing == null || state.sectionIndex < 0 || state.barsTotal <= 0) return null
        val seed = seedMsPerCycle(timing.stepCount, timing.bpm, timing.bpmMultiplier) ?: return null
        val now = nowMs()
        when {
            timing.vibeName != vibeName || timing.songId != songId -> {
                samples.clear()
                publishedMsPerCycle = null
                vibeName = timing.vibeName
                songId = timing.songId
                vibeStartedMs = now
                songMsBeforeSection = 0L
                startSection(state, timing.bpmMultiplier, now)
            }
            state.sectionIndex != sectionIndex || state.barsElapsed < barsAtBoundary -> {
                // The engine's state lags a vibe change by a poll, so a boundary this soon after
                // one is the old song's section giving way to the new song's start, not song time.
                val staleStart = now - vibeStartedMs < seed / 2
                songMsBeforeSection = if (staleStart) 0L else songMsBeforeSection + (sectionBarsTotal * msPerCycle).toLong()
                if (state.sectionIndex == sectionIndex) {
                    // A terminal section re-entered: the boundary is a loop-cycle like any other.
                    addSample(now - boundaryMs)
                } else {
                    // Cycle length is inversely proportional to tempo, so rescale rather than discard.
                    val scale = multiplier / timing.bpmMultiplier
                    val rescaled = samples.map { (it * scale).toLong() }
                    samples.clear()
                    samples.addAll(rescaled)
                    publishedMsPerCycle = publishedMsPerCycle?.let { it * scale }
                }
                startSection(state, timing.bpmMultiplier, now)
            }
            state.barsElapsed != barsAtBoundary -> {
                addSample(now - boundaryMs)
                barsAtBoundary = state.barsElapsed
                boundaryMs = now
            }
        }
        msPerCycle = publishedMs() ?: seed
        sectionBarsTotal = state.barsTotal
        val sectionEndMs = songMsBeforeSection + (state.barsTotal * msPerCycle).toLong()
        val positionMs = songMsBeforeSection + (state.barsElapsed * msPerCycle).toLong()
        val durationMs = when {
            state.sectionIndex == timing.finalSectionIndex -> sectionEndMs
            positionMs < timing.estimatedMs -> timing.estimatedMs
            // The ending is forced at the maximum; past that it is off, and the end rolls a section at a time.
            positionMs < timing.maxMs -> timing.maxMs
            else -> sectionEndMs
        }
        return PlaybackProgress(positionMs = positionMs.coerceIn(0L, durationMs), durationMs = durationMs)
    }

    private fun startSection(state: PulsarArrangementState, bpmMultiplier: Float, now: Long) {
        sectionIndex = state.sectionIndex
        multiplier = bpmMultiplier
        barsAtBoundary = state.barsElapsed
        boundaryMs = now
    }

    private fun addSample(intervalMs: Long) {
        samples.addLast(intervalMs)
        if (samples.size > CycleWindow) samples.removeFirst()
    }

    // A new duration is a Media3 timeline change, which re-sends the metadata and its artwork;
    // position alone does not. So the published length moves only for a real tempo change.
    private fun publishedMs(): Float? {
        val measured = measuredMs() ?: return null
        val published = publishedMsPerCycle
        if (published != null && abs(measured - published) <= CycleJitterToleranceMs) return published
        publishedMsPerCycle = measured
        return measured
    }

    // Median: a pause or stall is one outlier and does not move it.
    private fun measuredMs(): Float? {
        if (samples.size < MinCycleSamples) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toFloat() else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}

/** 16 steps a bar, 4 a beat: a step is 15000/bpm ms, and a loop-cycle is [stepCount] steps. */
internal fun seedMsPerCycle(stepCount: Int, bpm: Float, bpmMultiplier: Float): Float? {
    val effectiveBpm = bpm * bpmMultiplier
    if (stepCount <= 0 || effectiveBpm <= 0f) return null
    return stepCount * 15_000f / effectiveBpm
}

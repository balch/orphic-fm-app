package org.balch.orpheus.djapp

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.features.pulsar.SongStory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** Loudness rises in about a frame or two and falls over a quarter second, so hits read and tails don't flicker. */
private const val AttackMillis = 30f
private const val ReleaseMillis = 250f

/** The zip: one sweep from the path's start to the playhead, then a rest. */
internal const val ZipPassMillis = 1600
internal const val ZipRestMillis = 800

/** Half the zip band's width, of the elapsed path: a raised cosine whose bright half spans about 15%. */
internal const val ZipHalfWidth = 0.15f

/** The band fades in at the start of a pass and out at the playhead, over this much of it. */
private const val ZipFade = 0.15f

/**
 * The track colours mixed by level. Weights are squared so the loudest tracks lead; silence
 * (every level 0) is [fallback].
 */
internal fun blendTrackColors(levels: FloatArray, colors: List<Color>, fallback: Color): Color =
    blendTrackColors(levels, 0, min(levels.size, colors.size), colors, fallback)

/** [blendTrackColors] over the [count] levels from [from] in [levels]: one slice of a flat array. */
private fun blendTrackColors(levels: FloatArray, from: Int, count: Int, colors: List<Color>, fallback: Color): Color {
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    for (t in 0 until count) {
        val level = levels[from + t].coerceAtLeast(0f)
        val w = level * level
        if (w == 0f) continue
        val c = colors[t]
        r += c.red * w
        g += c.green * w
        b += c.blue * w
        total += w
    }
    return if (total == 0f) fallback else Color(r / total, g / total, b / total)
}

// The song up to [elapsedMs] in [buckets] even slices: each beat falls in every slice its span
// touches, so a slice narrower than a beat is never left empty, and a stretch with no beats stays
// empty. Beats past the elapsed position (the loop-cycle in flight) wait for it.
private inline fun SongStory.forEachBucket(buckets: Int, elapsedMs: Long, action: (bucket: Int, beat: Int) -> Unit) {
    if (elapsedMs <= 0L) return
    for (i in beats.indices) {
        val beat = beats[i]
        if (beat.positionMs < 0L || beat.positionMs >= elapsedMs) continue
        val end = minOf(beat.positionMs + beat.durationMs, elapsedMs)
        val first = (beat.positionMs * buckets / elapsedMs).toInt()
        val last = ((end * buckets + elapsedMs - 1) / elapsedMs).toInt() - 1
        for (k in first..maxOf(first, last).coerceAtMost(buckets - 1)) action(k, i)
    }
}

/**
 * Each of the first [buckets] slices' loudest beat up to [elapsedMs] into the song, into [levels], 0
 * where nothing was recorded; slice k sits at (k + ½) / buckets along the path. A cache refills one array.
 */
internal fun storyLevelsInto(levels: FloatArray, buckets: Int, story: SongStory, elapsedMs: Long) {
    levels.fill(0f, 0, buckets)
    story.forEachBucket(buckets, elapsedMs) { k, i -> levels[k] = maxOf(levels[k], story.beats[i].level) }
}

/**
 * Each of the first [buckets] slices' colour, into [out] as packed [Color.value]s: its beats'
 * summed track energy, blended as [blendTrackColors] does, [fallback] where none. The energy sums
 * in [energy] (at least [buckets] × the colours' count): a cache refills both rather than allocating.
 */
internal fun storyColorsInto(
    out: LongArray,
    energy: FloatArray,
    buckets: Int,
    story: SongStory,
    colors: List<Color>,
    fallback: Color,
    elapsedMs: Long,
) {
    val tracks = colors.size
    energy.fill(0f, 0, buckets * tracks)
    story.forEachBucket(buckets, elapsedMs) { k, i ->
        val beat = story.beats[i].trackEnergy
        for (t in 0 until min(beat.size, tracks)) energy[k * tracks + t] += beat[t]
    }
    for (k in 0 until buckets) out[k] = blendTrackColors(energy, k * tracks, tracks, colors, fallback).value.toLong()
}

/** One frame of [current] easing toward [target] over [dtMs]: fast up, slow down. */
internal fun smoothLevel(current: Float, target: Float, dtMs: Float): Float {
    val tau = if (target > current) AttackMillis else ReleaseMillis
    return current + (target - current) * (1f - exp(-dtMs.coerceAtLeast(0f) / tau))
}

/** The beat phase carried on at tempo since the last pulse, at most a step (a quarter beat) ahead of it. */
internal fun extrapolatedBeatPhase(beatPhase: Float, msSincePulse: Float, msPerBeat: Float): Float {
    if (msPerBeat <= 0f) return beatPhase
    return beatPhase + (msSincePulse.coerceAtLeast(0f) / msPerBeat).coerceAtMost(0.25f)
}

private fun easeInOut(u: Float): Float = u * u * (3f - 2f * u)

/** Where the zip band's centre is, 0..1 along the elapsed path, [timeMs] into the zip. Parked at 1 through the rest. */
internal fun zipCentre(timeMs: Long): Float {
    val t = timeMs.mod((ZipPassMillis + ZipRestMillis).toLong())
    return easeInOut((t.toFloat() / ZipPassMillis).coerceAtMost(1f))
}

/**
 * How lit a point at [position] (0..1 along the elapsed path) is, [timeMs] into the zip: a soft
 * band travelling start to playhead over [ZipPassMillis], fading in and out at the ends, then
 * dark for [ZipRestMillis].
 */
internal fun zipIntensity(position: Float, timeMs: Long): Float {
    val t = timeMs.mod((ZipPassMillis + ZipRestMillis).toLong())
    if (t >= ZipPassMillis) return 0f
    val u = t.toFloat() / ZipPassMillis
    val d = abs(position - easeInOut(u)) / ZipHalfWidth
    if (d >= 1f) return 0f
    val fade = (min(u, 1f - u) / ZipFade).coerceIn(0f, 1f)
    return fade * (1f + cos(PI.toFloat() * d)) / 2f
}

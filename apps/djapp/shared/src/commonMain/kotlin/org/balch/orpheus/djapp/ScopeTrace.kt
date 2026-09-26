package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.viz.ScopeFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The gain follows a louder window in about this long and a quieter one over this, so a hit swells and its tail shows. */
internal const val ScopeAttackMillis = 50f
internal const val ScopeReleaseMillis = 800f

/** The gain's ceiling, as the quietest peak that still draws at full height: about -40 dBFS. */
internal const val ScopeEnvelopeFloor = 0.01f

/** Of the last trace, this much stays each 16ms: the trace breathes rather than flickers. */
internal const val ScopeSmoothing = 0.3f

/** With no trigger (noise, or a hit that has not repeated yet) the last shape holds this long, then fades to the plain arc. */
internal const val ScopeHoldMillis = 250f
internal const val ScopeHoldFadeMillis = 600f

/** One step of the frame loop, and the longest a single update may count: a resume after a pause is one frame, not minutes. */
private const val ScopeStepMillis = 16f
private const val ScopeMaxStepMillis = 100L

private const val NoTime = Long.MIN_VALUE

/**
 * The master-mix scope the transport rings trace. A ring holds it while it plays off TV hardware in
 * a started app, and the engine polls the scope only while any ring holds it. Provided once at the
 * app root, so every chrome's ring finds it. Main thread only.
 */
@Stable
internal class ScopeFeed(val frame: ScopeFrame, private val enable: (Boolean) -> Unit) {
    private var holders = 0

    fun hold() {
        if (holders++ == 0) enable(true)
    }

    fun release() {
        if (holders > 0 && --holders == 0) enable(false)
    }
}

/** Unprovided in previews and most tests: there a ring keeps its beat wave. */
internal val LocalScopeFeed = staticCompositionLocalOf<ScopeFeed?> { null }

@Composable
internal fun rememberScopeFeed(engine: SynthEngine): ScopeFeed =
    remember(engine) { ScopeFeed(engine.scopeFrame) { engine.setScopeEnabled(it) } }

/**
 * A ring's trace of the scope: each new window's shape, auto-gained and blended into the last so
 * the trace breathes. An untriggered window keeps the last triggered shape, swelled or shrunk to its
 * loudness, and a long run of them fades it to the plain arc. Refilled in place by the frame loop,
 * read in draw by [average]; nothing here allocates once the first window has sized it.
 * An engine whose scope never produces a window leaves it empty, and the ring flat.
 */
internal class ScopeTrace {
    private var raw = FloatArray(0)
    private var shape = FloatArray(0)
    private var points = FloatArray(0)
    // sums[i] is the sum of the first i points, so any stretch averages in two reads.
    private var sums = FloatArray(1)
    private var version = 0
    private var envelope = ScopeEnvelopeFloor
    private var lastMs = NoTime
    private var triggeredMs = NoTime
    private var shaped = false

    /** Points in the trace; 0 until the first window. */
    var size = 0
        private set

    /** The trace at point [i], -1..1 of the ring's height. */
    operator fun get(i: Int): Float = points[i]

    /**
     * Takes [frame]'s window if it is new, at [nowMs] on a clock that runs only while playing.
     * Returns whether the trace changed.
     */
    fun update(frame: ScopeFrame, nowMs: Long): Boolean {
        val v = frame.version
        if (v == version) return false
        version = v
        val n = frame.size
        if (n <= 0) return false
        fit(n)
        val triggered = frame.readInto(raw)
        val dt = if (lastMs == NoTime) ScopeStepMillis else (nowMs - lastMs).coerceIn(0L, ScopeMaxStepMillis).toFloat()
        lastMs = nowMs
        var peak = 0f
        for (i in 0 until n) peak = max(peak, abs(raw[i]))
        if (!peak.isFinite()) return false
        val tau = if (peak > envelope) ScopeAttackMillis else ScopeReleaseMillis
        envelope = max(ScopeEnvelopeFloor, envelope + (peak - envelope) * (1f - exp(-dt / tau)))
        if (triggered && peak > 0f) {
            for (i in 0 until n) shape[i] = raw[i] / peak
            shaped = true
            triggeredMs = nowMs
        }
        // Square-root compression: a tail a quarter as loud as the hit still draws half as tall.
        val scale = if (!shaped) 0f else sqrt(min(1f, peak / envelope)) * holdFade(nowMs - triggeredMs)
        val keep = ScopeSmoothing.pow(dt / ScopeStepMillis)
        for (i in 0 until n) {
            val target = shape[i] * scale
            points[i] = target + (points[i] - target) * keep
            sums[i + 1] = sums[i] + points[i]
        }
        return true
    }

    /**
     * Takes [frame]'s window whole, as if it had played steadily: for render-harness seams, which
     * pin a frame rather than run the loop.
     */
    fun prime(frame: ScopeFrame) {
        val n = frame.size
        if (n <= 0) return
        fit(n)
        frame.readInto(raw)
        var peak = 0f
        for (i in 0 until n) peak = max(peak, abs(raw[i]))
        val gain = if (peak > 0f && peak.isFinite()) sqrt(min(1f, peak / ScopeEnvelopeFloor)) / peak else 0f
        for (i in 0 until n) {
            points[i] = raw[i] * gain
            sums[i + 1] = sums[i] + points[i]
        }
        version = frame.version
    }

    /** The trace's mean over positions [from] to [to], in points (0..[size]), clamped to the trace. */
    fun average(from: Float, to: Float): Float {
        val n = size
        if (n == 0) return 0f
        val lo = from.coerceIn(0f, n.toFloat())
        val hi = to.coerceIn(0f, n.toFloat())
        if (hi - lo < 1e-3f) return points[lo.toInt().coerceAtMost(n - 1)]
        return (sumTo(hi) - sumTo(lo)) / (hi - lo)
    }

    private fun sumTo(x: Float): Float {
        val k = x.toInt()
        return if (k >= size) sums[size] else sums[k] + points[k] * (x - k)
    }

    private fun fit(n: Int) {
        if (n == size) return
        raw = FloatArray(n)
        shape = FloatArray(n)
        points = FloatArray(n)
        sums = FloatArray(n + 1)
        size = n
        shaped = false
    }
}

/** The held shape's share of its height [heldMs] after the last trigger. */
internal fun holdFade(heldMs: Long): Float =
    if (heldMs <= ScopeHoldMillis) 1f else exp(-(heldMs - ScopeHoldMillis) / ScopeHoldFadeMillis)

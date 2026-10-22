package org.balch.orpheus.features.visualizations.viz

import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.math.exp

/** Pump rise and relax, in beats, so a fast song beats and a slow one sways at the same shape. */
internal const val PUMP_ATTACK_BEATS = 0.08f
internal const val PUMP_RELEASE_BEATS = 0.6f

/** A 16th at 120 bpm: the step length assumed until the playheads have shown the tempo. */
private const val DEFAULT_STEP_SECONDS = 0.125f

/**
 * Each Pulsar track's pump, 0..1: a new gated step pushes it toward that step's velocity, and it
 * relaxes between hits. Rise and relax are measured in beats, from how fast track 0's playhead
 * moves, so the lanes beat with a fast groove and swell and sway with a slow one.
 */
internal class LanePump(private val tracks: Int) {
    /** The pumps, one per track. */
    val level = FloatArray(tracks)

    private val target = FloatArray(tracks)
    private val lastPlayheads = IntArray(tracks) { -1 }
    private var lastTime = -1.0
    private var lastStepAt = -1.0

    /** Seconds per step, smoothed from track 0's playhead. */
    var stepSeconds = DEFAULT_STEP_SECONDS
        private set

    /** Takes the steps that started since the last call, then moves the pumps to [now] (seconds). */
    fun update(now: Double, viz: PulsarVizData) {
        for (t in 0 until tracks) {
            val ph = viz.playheads.getOrElse(t) { -1 }
            if (ph == lastPlayheads[t]) continue
            lastPlayheads[t] = ph
            if (ph < 0) continue
            if (t == 0) {
                // Poll jitter is a frame either way, so smooth; a gap far off a 16th is a stop or a jump.
                val gap = now - lastStepAt
                if (lastStepAt >= 0 && gap in 0.03..0.5) stepSeconds += (gap.toFloat() - stepSeconds) * 0.2f
                lastStepAt = now
            }
            val gates = viz.stepGates.getOrNull(t) ?: continue
            if (ph < gates.size && gates[ph]) {
                val v = viz.stepVelocities[t][ph]
                if (v > target[t]) target[t] = v
            }
        }
        val dt = if (lastTime < 0) 0f else (now - lastTime).toFloat().coerceIn(0f, 0.1f)
        lastTime = now
        val beat = stepSeconds * 4f
        val rise = 1f - exp(-dt / (beat * PUMP_ATTACK_BEATS))
        val relax = exp(-dt / (beat * PUMP_RELEASE_BEATS))
        for (t in 0 until tracks) {
            level[t] += (target[t] - level[t]) * rise
            target[t] *= relax
        }
    }
}

package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A dramatic event armed around a section flip, dispatched by C++ at the flip sample (plus a
 * tiny pre-roll scheduler for negative [StrikeEffect.offsetBars]). Authored on a single outgoing
 * [SectionTransition] edge, or on a whole [Section] via `exitEffects` (it ends) / `entryEffects`
 * (it begins); all three carry any mix of scratch / tape-stop / lightning-strike moments,
 * sequenced by list order and offset.
 *
 * **Transitions always win.** An armed effect (re)arms its target even over an anomaly already
 * in flight on the same target — the anomaly dispatch keeps its own guards, this path doesn't.
 */
@Serializable
sealed interface TransitionEffect {
    companion object {
        /**
         * Effects that can fire at ONE section flip: it bounds an edge's own list, the departing
         * section's `exitEffects`, the arriving section's `entryEffects`, and — since the three
         * are a union — their combined total ([Arrangement] checks that, the only place all three
         * are visible). That is exactly the `kMaxPendingFx` budget in `pulsar_transition_fx.h`
         * this mirrors; C++ stages at most that many and silently drops the rest, so authoring
         * must fail loudly first.
         */
        const val MAX_PER_FLIP = 4
    }
}

/**
 * Freezes the outgoing section's clock and grabs the audio — same as the retired `exitScratchMs`.
 * SerialName is "scratchEffect", not "scratch": [org.balch.orpheus.features.pulsar.anonmalies.ScratchAnomaly]
 * already claims "scratch", and the Vibe-wide JSON schema generator (unlike kotlinx.serialization's
 * own per-hierarchy discriminator scoping) requires every polymorphic tag in the graph to be
 * globally unique.
 */
@Serializable
@SerialName("scratchEffect")
data class ScratchEffect(val ms: Int = 500) : TransitionEffect {
    init {
        require(ms > 0) { "ScratchEffect.ms must be > 0, got $ms" }
    }
}

/** Varispeed tape-stop carrying the outgoing section's audio through the flip. */
@Serializable
@SerialName("tapeStop")
data class TapeStopEffect(val ms: Int = 800) : TransitionEffect {
    init {
        require(ms > 0) { "TapeStopEffect.ms must be > 0, got $ms" }
    }
}

/**
 * A lightning-strike burst (crack + rumble tail) timed against the flip.
 * @param intensity Strike/rumble loudness, 0-1.
 * @param distance Perceived distance, 0 (near, sharp crack) to 1 (far, cracks suppressed).
 * @param offsetBars Loop-units relative to the flip downbeat: negative fires during the edge's
 *   pre-roll, 0 lands on the downbeat itself. Legal range enforced by whichever list holds it —
 *   [SectionTransition.init] or [Section.init] — a fixed -8..1 bound, independent of the edge's
 *   own `transitionBars`.
 * @param delayMs Sub-bar wait AFTER the [offsetBars] fire point, 0..[MAX_DELAY_MS]. This is how
 *   two strikes are authored as a sequence: without it both land in the same block and the
 *   second's clap cascade truncates the first's. Milliseconds, not beats — thunder is physical
 *   and must not stretch with the BPM — and orthogonal to [offsetBars], so "one bar later, then
 *   400 ms after that" is `offsetBars = 1f, delayMs = 400`. The cascade itself runs ~96 ms, so a
 *   gap under that still collides. Crosses the wire as the strike row's free `p2` slot.
 */
@Serializable
@SerialName("strike")
data class StrikeEffect(
    val intensity: Float = 0.8f,
    val distance: Float = 0.2f,
    val offsetBars: Float = 0f,
    val delayMs: Int = 0,
) : TransitionEffect {
    init {
        require(intensity in 0f..1f) { "StrikeEffect.intensity must be 0..1, got $intensity" }
        require(distance in 0f..1f) { "StrikeEffect.distance must be 0..1, got $distance" }
        require(delayMs in 0..MAX_DELAY_MS) {
            "StrikeEffect.delayMs must be 0..$MAX_DELAY_MS, got $delayMs"
        }
    }

    companion object {
        /**
         * Ceiling on [delayMs]. Two seconds is already a slow answering crack; anything
         * further out is a bar away, which is what [offsetBars] is for. Mirrored as
         * `kStrikeMaxDelayMs` in `liborpheus_dsp/src/pulsar_storm.h`, which clamps to it.
         */
        const val MAX_DELAY_MS = 2000
    }
}

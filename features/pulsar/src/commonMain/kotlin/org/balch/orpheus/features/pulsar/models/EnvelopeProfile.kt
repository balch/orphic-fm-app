package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Envelope shape categories — controls attack/decay/sustain character.
 * - RHYTHM: Short, punchy (kicks, snares, hats)
 * - MELODIC: Medium attack, sustain, clean release (bass, keys)
 * - EFFECT: Slow attack, long tail (pads, textures)
 * - WILD: Unpredictable, macro-driven (experimental tracks)
 * - DRONE: Very slow attack, infinite sustain while gate held (ambient pads)
 */
@Serializable
enum class EnvelopeProfile(val id: Int) {
    RHYTHM(0),
    MELODIC(1),
    EFFECT(2),
    WILD(3),
    DRONE(4),
}

/**
 * How a track's pattern evolves across bars.
 * - REPEAT: Same pattern every bar (driving grooves, anchoring elements)
 * - MUTATE: Slightly varies each bar (keeps interest without losing feel)
 * - FILL: Adds fills at phrase boundaries (snare rolls, tom fills)
 * - CALL_RESPONSE: Alternates between two complementary patterns
 * - INDEPENDENT: Fully regenerated each bar (textures, atmospheric elements)
 */
@Serializable
enum class BarStrategy(val id: Int) {
    REPEAT(0),
    MUTATE(1),
    FILL(2),
    CALL_RESPONSE(3),
    INDEPENDENT(4),
}

/**
 * Per-voice low-pass gate mode. Models the vactrol-based LPG that hardware Plaits
 * applies after engine rendering. Orpheus bypasses Plaits' built-in LPG and lets
 * each [OrpheusEngine] opt in.
 *
 * Set [OrpheusEngine.lpgMode] explicitly to control the vactrol — e.g. force
 * [BYPASS] on a Modal-engine drone, or force [PLUCK] on a Waveshaping-engine bass
 * for an articulate attack.
 */
@Serializable
enum class LpgMode(val id: Int) {
    /** Skip the LPG. Raw engine output, no envelope/filter shaping. */
    BYPASS(0),
    /** Vactrol follows the gate — open while held, natural decay on release. */
    SUSTAINED(1),
    /** Vactrol bloom on note-on, asymmetric decay regardless of gate length. */
    PLUCK(2),
    /** Sentinel — resolved to per-engine default in C++. */
    ENGINE_DEFAULT(3),
    /**
     * [PLUCK] whose held notes are re-picked on every step of the hold chain, so a
     * 1-beat lick note is four 16th picks. The double-picked riff 2.0.5 produced by
     * accident when its gate timer dropped between hold steps; opt in per voice.
     */
    PLUCK_REPEAT(4),
    /**
     * [PLUCK_REPEAT] on the 8th grid: held notes re-pick on the beat and the "&". Grids
     * follow the beat, not the note, and a note's first pick always plays.
     */
    PLUCK_REPEAT_8TH(5),
    /** [PLUCK_REPEAT] on the off-beat 8ths only: held notes re-pick on the "&". */
    PLUCK_REPEAT_8TH_OFF(6),
    /**
     * [PLUCK_REPEAT] on 8th-note triplets: held notes re-pick on the beat and a third and
     * two thirds into it. A third that falls right after a note's first pick is skipped.
     */
    PLUCK_REPEAT_TRIPLET(7);
}

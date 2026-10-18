package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * How a step note becomes grains on a Mist track. The [id] crosses the wire as a bare
 * int and is mirrored by `GrainModeId` in orpheus_unit_pulsar.h.
 */
@Serializable
enum class GrainMode(val id: Int) {
    /** Grains spray at [OrpheusEngine.grainRate] for as long as the note is held. */
    BURST(0),
    /** One grain per note. */
    SINGLE(1),
    /**
     * Grains follow Pulsar's step clock and never wait for a note; notes only move the
     * pitch new grains are born at. [OrpheusEngine.grainRate] is then a clock divider
     * (even timing) or a per-step probability (random timing), and 0 is silence.
     */
    CONTINUOUS(2),
}

/** Where a scattered grain lands. Mirrored by `GrainScatterModeId` in orpheus_unit_pulsar.h. */
@Serializable
enum class GrainScatterMode(val id: Int) {
    /** A tone of the bar's chord, in any octave inside the scatter span. */
    CHORD(0),
    /** Any degree of the vibe's scale inside the span. */
    SCALE(1),
    /** Unquantized random detune: shimmer when small, atonal smear when large. */
    RAW(2),
}

/**
 * Which tone a scattered grain takes, in CHORD and SCALE scatter. Mirrored by
 * `GrainVoicingId` in orpheus_unit_pulsar.h.
 */
@Serializable
enum class GrainVoicing(val id: Int) {
    /** Every tone in reach is equally likely: an even wash across the span. */
    EVEN(0),
    /** Clustered on the played note, thinning toward the edges: the note stays the centre. */
    CENTERED(1),
    /** Only the played note and the tones above it. */
    RISING(2),
}

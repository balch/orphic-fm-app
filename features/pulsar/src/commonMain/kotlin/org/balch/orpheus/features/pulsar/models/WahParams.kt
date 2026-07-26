package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Voice parameters for the tempo-synced bandpass wah. Mirrors the C++ `orpheus::WahParams`
 * (orpheus_wah_core.h) field-for-field. Powers two consumers, both of them per-track inserts
 * applied to a track's audio before it accumulates into the mix: the standing lick-wah insert
 * (`Vibe.lickWah` / `TrackRole.Melodic.wahLick`, always on for the tracks that opt in) and the
 * wah anomaly ([org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly], swept over its armed
 * duration on the vibe's lead tracks, marshaled into the `wah_data_*` bank).
 *
 * The two never stack. While the anomaly is armed on a track that already has the standing
 * insert, it drives that same filter with its own params for the duration instead of adding a
 * second bandpass in series.
 *
 * @param rateDivision LFO sweep rate as a note value (4 = quarter, 8 = eighth, 16 = sixteenth).
 * @param depth Sweep depth multiplier, 0 = static filter at [centerHz].
 * @param resonanceQ Bandpass peak Q (the "wah" vowel sharpness).
 * @param centerHz Center frequency the sweep pivots around.
 * @param sweepOctaves Total sweep span in octaves around [centerHz].
 * @param wet Dry/wet blend, 0 = bypass, 1 = fully filtered.
 */
@Serializable
data class WahParams(
    val rateDivision: Float = 8f,
    val depth: Float = 1f,
    val resonanceQ: Float = 3f,
    val centerHz: Float = 800f,
    val sweepOctaves: Float = 1.3f,
    val wet: Float = 1f,
) {
    companion object {
        /**
         * Fields marshalled per wah voice to C++, in declaration order. MUST equal the
         * field count of `orpheus::WahParams` and the C++ unpack stride.
         */
        const val FIELDS = 6
    }
}
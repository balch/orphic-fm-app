package org.balch.orpheus.core.audio.dsp

/**
 * Offsets into one flattened mix bench summary, mirroring `liborpheus_dsp/src/mix_bench.h`.
 * Ints are stored as floats. BenchSummaryLayoutTest parses the header to keep them in step.
 */
object BenchSummaryLayout {
    /** Floats per summary: kBenchSummaryFloats. */
    const val FLOATS = 126

    // Header, in BenchSummary order.
    const val FRAMES = 0
    const val SECTION = 1
    const val PRE_LIMIT_PEAK = 2
    const val E_PRE_LIMIT = 3
    const val E_POST_LIMIT = 4
    const val E_PRE_HPF = 5
    const val E_POST_HPF = 6
    const val OVER_KNEE = 7
    const val SAMPLES = 8
    const val HEADER = 9

    // Per-channel fields, in BenchChannel order. wave_* is the block's min and max of (L+R)/2.
    const val K_ENERGY = 0
    const val E_L = 1
    const val E_R = 2
    const val E_LR = 3
    const val E_SUB = 4
    const val E_LOW = 5
    const val PEAK = 6
    const val WAVE_MIN = 7
    const val WAVE_MAX = 8
    const val CHANNEL_FLOATS = 9

    // Channels: 0-7 tracks, then the voices Pulsar mixes outside them, the dry sum and the output.
    const val TRACKS = 8
    const val STORM = 8
    const val STREET = 9
    const val SPEECH = 10
    const val DRY = 11
    const val OUT = 12
    const val CHANNELS = 13

    val HEADER_FIELDS = listOf(
        "frames", "section", "pre_limit_peak", "e_pre_limit", "e_post_limit",
        "e_pre_hpf", "e_post_hpf", "over_knee", "samples",
    )
    val CHANNEL_FIELDS = listOf("k_energy", "e_l", "e_r", "e_lr", "e_sub", "e_low", "peak", "wave_min", "wave_max")

    /** Offset of channel [c]'s first field within a summary. */
    fun channel(c: Int): Int = HEADER + c * CHANNEL_FLOATS
}

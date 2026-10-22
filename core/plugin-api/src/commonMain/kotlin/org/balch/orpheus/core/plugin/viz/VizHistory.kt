package org.balch.orpheus.core.plugin.viz

import kotlin.time.TimeSource

/** Viz channel ids, as `VizChannel` in liborpheus_dsp's orpheus_viz.h numbers them. */
object VizChannel {
    const val LFO = 0
    const val WARPS_CARRIER = 1
    const val WARPS_MOD = 2
    const val WARPS_OUT = 3
    const val DELAY_IN = 4
    const val DELAY_FB = 5
    const val DELAY_OUT = 6
    const val REVERB_IN = 7
    const val REVERB_OUT = 8
    const val FLUX_CV = 9
    const val RESO_IN = 10
    const val RESO_OUT = 11
    const val DRUM_OUT = 12
    const val GRAINS_IN = 13
    const val GRAINS_OUT = 14
    const val LFO_CH1 = 15
    const val LFO_CH2 = 16
    const val LFO_CH3 = 17
    const val BASS_OUT = 18
    const val MASTER_OUT = 19
    const val HORN_IN = 20
    const val HORN_OUT = 21
    const val HORN_PHASE = 22
    const val WOOFER_PHASE = 23
    const val DJ_OUT = 24
    const val TIDES_CH0 = 25
    const val TIDES_CH1 = 26
    const val TIDES_CH2 = 27
    const val TIDES_CH3 = 28
    const val PULSAR_TRACK_0 = 29
    const val BEAT_PHASE = 37
    const val PULSAR_VOID_GAIN = 38
    const val PULSAR_KRAKEN = 39
    const val COUNT = 40
}

/**
 * One poll's viz rings, indexed by [VizChannel] id, with the tick count just past each channel's
 * newest sample and when the poll took them. Enough to scroll the rings at an even speed.
 *
 * A ring holds [RING_SIZE] samples at [RATE] ticks a second whatever the device's audio buffer,
 * tick `c` at `c and (RING_SIZE - 1)`, or is empty when the poll skips the channel. The poll writes
 * the same arrays in place, so a reader stays within the newest [READABLE] samples of its [ends],
 * clear of the next polls' writes.
 */
class VizHistory(
    val samples: Array<FloatArray>,
    val ends: IntArray,
    val arrivedAt: TimeSource.Monotonic.ValueTimeMark,
) {
    companion object {
        /** Ticks a second: desktop's 48 kHz / 512-frame block rate, so a desktop ring maps block for tick. */
        const val RATE = 93.75

        /** About 43 s of ticks. A power of two, so slots survive the counts wrapping. */
        const val RING_SIZE = 4096

        /** The ring less ample room for the ticks later polls write past a reader's snapshot. */
        const val READABLE = RING_SIZE - 1024
    }
}

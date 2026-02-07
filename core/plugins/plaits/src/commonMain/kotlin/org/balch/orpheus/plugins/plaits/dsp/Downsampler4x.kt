package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsTables

/**
 * 4x FIR downsampler.
 * Ported from plaits/dsp/downsampler/4x_downsampler.h.
 *
 * Usage pattern (per output sample):
 * ```
 * val ds = Downsampler4x(stateVar)
 * for (j in 0 until 4) {
 *     ds.accumulate(j, oversampledSample)
 * }
 * output = ds.read()
 * stateVar = ds.state()
 * ```
 */
class Downsampler4x(initialState: Float = 0f) {
    private var head: Float = initialState
    private var tail: Float = 0f

    fun accumulate(j: Int, sample: Float) {
        head += sample * PlaitsTables.DOWNSAMPLER_FIR[3 - (j and 3)]
        tail += sample * PlaitsTables.DOWNSAMPLER_FIR[j and 3]
    }

    fun read(): Float {
        val value = head
        head = tail
        tail = 0f
        return value
    }

    fun state(): Float = head
}

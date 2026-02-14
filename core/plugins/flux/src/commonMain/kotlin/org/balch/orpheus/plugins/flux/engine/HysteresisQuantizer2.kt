// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from stmlib/dsp/hysteresis_quantizer.h (HysteresisQuantizer2).
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Quantize a float in [0, 1] to an integer in [0, numSteps).
 *  Applies hysteresis to prevent jumps near decision boundaries. */
class HysteresisQuantizer2 {
    private var numSteps = 0
    private var hysteresis = 0f
    private var scale = 0f
    private var offset = 0f
    private var quantizedValue = 0

    fun init(numSteps: Int, hysteresis: Float, symmetric: Boolean) {
        this.numSteps = numSteps
        this.hysteresis = hysteresis
        scale = if (symmetric) (numSteps - 1).toFloat() else numSteps.toFloat()
        offset = if (symmetric) 0f else -0.5f
        quantizedValue = 0
    }

    fun process(value: Float): Int = process(0, value)

    fun process(base: Int, value: Float): Int {
        var v = value * scale + offset + base.toFloat()
        val hysteresisSign = if (v > quantizedValue.toFloat()) -1f else 1f
        var q = (v + hysteresisSign * hysteresis + 0.5f).toInt()
        q = q.coerceIn(0, numSteps - 1)
        quantizedValue = q
        return q
    }

    /** Lookup an array element using hysteresis-quantized index. */
    fun <T> lookup(array: Array<T>, value: Float): T {
        return array[process(value)]
    }
}

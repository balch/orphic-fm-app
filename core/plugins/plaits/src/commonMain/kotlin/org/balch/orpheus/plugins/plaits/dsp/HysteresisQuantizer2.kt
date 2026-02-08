// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of stmlib/dsp/hysteresis_quantizer.h (HysteresisQuantizer2)

package org.balch.orpheus.plugins.plaits.dsp

/**
 * Simple quantizer with hysteresis for stable chord/mode selection.
 * Ported from stmlib HysteresisQuantizer2.
 */
class HysteresisQuantizer2 {
    private var numSteps = 0
    private var hysteresis = 0f
    private var scale = 0f
    private var offset = 0f
    private var quantizedValue = 0

    fun init(numSteps: Int, hysteresis: Float, symmetric: Boolean) {
        this.numSteps = numSteps
        this.hysteresis = hysteresis
        this.scale = if (symmetric) (numSteps - 1).toFloat() else numSteps.toFloat()
        this.offset = if (symmetric) 0.0f else -0.5f
        this.quantizedValue = 0
    }

    fun process(value: Float): Int = process(0, value)

    fun process(base: Int, value: Float): Int {
        var v = value * scale + offset + base.toFloat()
        val hysteresisSign = if (v > quantizedValue.toFloat()) -1.0f else 1.0f
        var q = (v + hysteresisSign * hysteresis + 0.5f).toInt()
        q = q.coerceIn(0, numSteps - 1)
        quantizedValue = q
        return q
    }

    fun quantizedValue(): Int = quantizedValue
}

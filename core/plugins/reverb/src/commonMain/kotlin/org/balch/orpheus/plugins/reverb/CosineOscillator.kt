// Copyright 2014 Emilie Gillet.
// Ported to Kotlin from stmlib/dsp/cosine_oscillator.h
// Licensed under MIT

package org.balch.orpheus.plugins.reverb

/**
 * Cosine oscillator using IIR approximation.
 * Generates a cosine between 0.0 and 1.0 with minimal CPU use.
 */
class CosineOscillator {
    private var y1 = 0f
    private var y0 = 0f
    private var iirCoefficient = 0f
    private var initialAmplitude = 0f

    fun initApproximate(frequency: Float) {
        var sign = 16.0f
        var f = frequency - 0.25f
        if (f < 0.0f) {
            f = -f
        } else {
            if (f > 0.5f) {
                f -= 0.5f
            } else {
                sign = -16.0f
            }
        }
        iirCoefficient = sign * f * (1.0f - 2.0f * f)
        initialAmplitude = iirCoefficient * 0.25f
        start()
    }

    private fun start() {
        y1 = initialAmplitude
        y0 = 0.5f
    }

    fun value(): Float = y1 + 0.5f

    fun next(): Float {
        val temp = y0
        y0 = iirCoefficient * y0 - y1
        y1 = temp
        return temp + 0.5f
    }
}

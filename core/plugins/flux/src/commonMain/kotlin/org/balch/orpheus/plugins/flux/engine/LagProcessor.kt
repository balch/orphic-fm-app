// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles random/lag_processor.cc.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Lag processor for the STEPS control.
 *
 *  Dual-mode smoother:
 *  - smoothness < 0.6: One-pole LP filter, frequency derived from ramp phase delta
 *  - smoothness >= 0.6: Raised-cosine-warped ramp interpolation between values
 *  - Crossfade between modes near the 0.6 boundary */
class LagProcessor {
    private var rampStart = 0f
    private var rampValue = 0f
    private var lpState = 0f
    private var previousPhase = 0f

    fun init() {
        rampStart = 0f
        rampValue = 0f
        lpState = 0f
        previousPhase = 0f
    }

    fun resetRamp() {
        rampStart = rampValue
    }

    fun process(value: Float, smoothness: Float, phase: Float): Float {
        var frequency = phase - previousPhase
        if (frequency < 0f) {
            frequency += 1f
        }
        previousPhase = phase

        // The frequency of the portamento/glide LP filter follows an exponential
        // scale, with a minimum frequency corresponding to half the clock pulse
        // frequency and a maximum 7 octaves above.
        frequency *= 0.25f
        frequency *= DspUtil.semitonesToRatio(84f * (1f - smoothness))
        if (frequency >= 1f) {
            frequency = 1f
        }
        // When smoothness approaches 0, tweak for immediate voltage changes
        if (smoothness <= 0.05f) {
            frequency += 20f * (0.05f - smoothness) * (1f - frequency)
        }

        lpState = DspUtil.onePole(lpState, value, frequency)

        // Crossfade between raised-cosine interpolation and LP glide
        var interpAmount = (smoothness - 0.6f) * 5f
        interpAmount = interpAmount.coerceIn(0f, 1f)

        var interpLinearity = (1f - smoothness) * 5f
        interpLinearity = interpLinearity.coerceIn(0f, 1f)
        val warpedPhase = DspUtil.interpolate(LookupTables.raisedCosine, phase, 256f)

        val interpPhase = DspUtil.crossfade(warpedPhase, phase, interpLinearity)
        val interp = DspUtil.crossfade(rampStart, value, interpPhase)
        rampValue = interp

        return DspUtil.crossfade(lpState, interp, interpAmount)
    }
}

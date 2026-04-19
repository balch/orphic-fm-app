// Copyright 2014 Emilie Gillet.
// Ported to Kotlin from rings/dsp/fx/reverb.h + fx_engine.h
// Licensed under MIT
//
// Dattorro plate reverb (Griesinger topology):
// 4 AP diffusers on input, then a loop of 2x (2AP + 1Delay).

package org.balch.orpheus.plugins.reverb

/**
 * Dattorro plate reverb ported from Mutable Instruments Rings.
 *
 * Uses float buffer directly (no 16-bit compression) for better quality
 * on desktop/mobile targets.
 *
 * Original delay line lengths are for 48kHz; scaled by [sampleRate] ratio.
 *
 * @param sampleRate the target sample rate (default 44100Hz for JVM/Android;
 *        pass the runtime rate on platforms where it varies, e.g. WASM WebAudio).
 */
class DattorroReverb(sampleRate: Float = 44100f) {

    companion object {
        private const val BUFFER_SIZE = 32768 // Power of 2 — must be >= total delay memory
        private const val MASK = BUFFER_SIZE - 1
        private const val REF_RATE = 48000f

        // Reference delay lengths at 48kHz
        private const val AP1_REF = 150; private const val AP2_REF = 214
        private const val AP3_REF = 319; private const val AP4_REF = 527
        private const val DAP1A_REF = 2182; private const val DAP1B_REF = 2690; private const val DEL1_REF = 4501
        private const val DAP2A_REF = 2525; private const val DAP2B_REF = 2197; private const val DEL2_REF = 6312
        private const val DEL2_TAP_REF = 6261f; private const val DEL2_LFO_AMP_REF = 50f
        private const val DEL1_TAP_REF = 4460f; private const val DEL1_LFO_AMP_REF = 40f
    }

    // Scale factor from 48kHz reference to target sample rate
    private val rateRatio = sampleRate / REF_RATE

    // Input allpass diffuser lengths (scaled)
    private val ap1Len = (AP1_REF * rateRatio).toInt()
    private val ap2Len = (AP2_REF * rateRatio).toInt()
    private val ap3Len = (AP3_REF * rateRatio).toInt()
    private val ap4Len = (AP4_REF * rateRatio).toInt()

    // Loop delay/allpass lengths (scaled)
    private val dap1aLen = (DAP1A_REF * rateRatio).toInt()
    private val dap1bLen = (DAP1B_REF * rateRatio).toInt()
    private val del1Len  = (DEL1_REF * rateRatio).toInt()
    private val dap2aLen = (DAP2A_REF * rateRatio).toInt()
    private val dap2bLen = (DAP2B_REF * rateRatio).toInt()
    private val del2Len  = (DEL2_REF * rateRatio).toInt()

    // Bases (cumulative offsets in buffer)
    private val ap1Base  = 0
    private val ap2Base  = ap1Base  + ap1Len + 1
    private val ap3Base  = ap2Base  + ap2Len + 1
    private val ap4Base  = ap3Base  + ap3Len + 1
    private val dap1aBase = ap4Base + ap4Len + 1
    private val dap1bBase = dap1aBase + dap1aLen + 1
    private val del1Base  = dap1bBase + dap1bLen + 1
    private val dap2aBase = del1Base + del1Len + 1
    private val dap2bBase = dap2aBase + dap2aLen + 1
    private val del2Base  = dap2bBase + dap2bLen + 1

    // LFO tap offsets (scaled)
    private val del2Tap = DEL2_TAP_REF * rateRatio
    private val del2LfoAmp = DEL2_LFO_AMP_REF * rateRatio
    private val del1Tap = DEL1_TAP_REF * rateRatio
    private val del1LfoAmp = DEL1_LFO_AMP_REF * rateRatio

    private val buffer = FloatArray(BUFFER_SIZE)
    private var writePtr = 0

    // LFOs (updated every 32 samples)
    private val lfo1 = CosineOscillator()
    private val lfo2 = CosineOscillator()
    private var lfoValue0 = 0f
    private var lfoValue1 = 0f

    // Parameters
    var amount = 0.3f
    var inputGain = 0.5f
    var reverbTime = 0.5f
    var diffusion = 0.625f
    var lp = 0.7f

    // LP decay state
    private var lpDecay1 = 0f
    private var lpDecay2 = 0f

    // Reusable output fields (avoids Pair allocation in audio thread)
    var outLeft = 0f
        private set
    var outRight = 0f
        private set

    init {
        // LFO frequencies scaled for the target sample rate
        lfo1.initApproximate(0.5f / sampleRate * 32f)
        lfo2.initApproximate(0.3f / sampleRate * 32f)
    }

    fun clear() {
        buffer.fill(0f)
        writePtr = 0
        lpDecay1 = 0f
        lpDecay2 = 0f
    }

    /**
     * Process one stereo frame.
     * Results are stored in [outLeft] and [outRight] to avoid allocation.
     */
    fun process(leftIn: Float, rightIn: Float) {
        val kap = diffusion
        val klp = lp
        val krt = reverbTime
        val gain = inputGain

        // Advance write pointer
        writePtr = (writePtr - 1 + BUFFER_SIZE) and MASK

        // Update LFOs every 32 samples
        if ((writePtr and 31) == 0) {
            lfoValue0 = lfo1.next()
            lfoValue1 = lfo2.next()
        }

        // Context state
        var acc = 0f
        var prevRead = 0f

        // ---- Read input (mono sum) ----
        acc = (leftIn + rightIn) * gain

        // ---- 4 input allpass diffusers ----
        acc = allpass(ap1Base, ap1Len, acc, kap)
        acc = allpass(ap2Base, ap2Len, acc, kap)
        acc = allpass(ap3Base, ap3Len, acc, kap)
        acc = allpass(ap4Base, ap4Len, acc, kap)

        val apout = acc

        // ---- Main reverb loop: Path 1 (left output) ----
        acc = apout
        acc += interpolate(del2Base, del2Tap, lfoValue1, del2LfoAmp) * krt
        lpDecay1 += klp * (acc - lpDecay1)
        acc = lpDecay1
        acc = allpass(dap1aBase, dap1aLen, acc, -kap)
        acc = allpass(dap1bBase, dap1bLen, acc, kap)
        writeBuffer(del1Base, acc)
        val wetLeft = acc * 2f

        // ---- Main reverb loop: Path 2 (right output) ----
        acc = apout
        acc += interpolate(del1Base, del1Tap, lfoValue0, del1LfoAmp) * krt
        lpDecay2 += klp * (acc - lpDecay2)
        acc = lpDecay2
        acc = allpass(dap2aBase, dap2aLen, acc, kap)
        acc = allpass(dap2bBase, dap2bLen, acc, -kap)
        writeBuffer(del2Base, acc)
        val wetRight = acc * 2f

        // ---- Output wet-only (parallel send — dry signal handled externally) ----
        outLeft = wetLeft * amount
        outRight = wetRight * amount
    }

    /** Single allpass section: read tail, feedforward/feedback, write head. */
    private fun allpass(base: Int, len: Int, input: Float, coeff: Float): Float {
        val tail = readBuffer(base + len - 1)
        val v = input + tail * coeff
        writeBuffer(base, v)
        return v * (-coeff) + tail
    }

    private fun readBuffer(offset: Int): Float {
        return buffer[(writePtr + offset) and MASK]
    }

    private fun writeBuffer(offset: Int, value: Float) {
        buffer[(writePtr + offset) and MASK] = value
    }

    /**
     * Interpolated read with LFO modulation (mirrors FxEngine::Context::Interpolate).
     */
    private fun interpolate(base: Int, offset: Float, lfoVal: Float, amplitude: Float): Float {
        val modulatedOffset = offset + amplitude * lfoVal
        val intPart = modulatedOffset.toInt()
        val fracPart = modulatedOffset - intPart
        val a = buffer[(writePtr + intPart + base) and MASK]
        val b = buffer[(writePtr + intPart + base + 1) and MASK]
        return a + (b - a) * fracPart
    }
}

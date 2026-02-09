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
 * Original delay line lengths are for 48kHz; scaled by RATE_RATIO for 44100Hz.
 */
class DattorroReverb {

    companion object {
        private const val BUFFER_SIZE = 32768 // Power of 2 — must be >= total delay memory (~19866 at 44.1kHz)
        private const val MASK = BUFFER_SIZE - 1

        // Scale factor from 48kHz → 44100Hz
        private const val RATE_RATIO = 44100f / 48000f

        // Input allpass diffuser lengths (scaled)
        private val AP1_LEN = (150 * RATE_RATIO).toInt()
        private val AP2_LEN = (214 * RATE_RATIO).toInt()
        private val AP3_LEN = (319 * RATE_RATIO).toInt()
        private val AP4_LEN = (527 * RATE_RATIO).toInt()

        // Loop delay/allpass lengths (scaled)
        private val DAP1A_LEN = (2182 * RATE_RATIO).toInt()
        private val DAP1B_LEN = (2690 * RATE_RATIO).toInt()
        private val DEL1_LEN  = (4501 * RATE_RATIO).toInt()
        private val DAP2A_LEN = (2525 * RATE_RATIO).toInt()
        private val DAP2B_LEN = (2197 * RATE_RATIO).toInt()
        private val DEL2_LEN  = (6312 * RATE_RATIO).toInt()

        // Bases (cumulative offsets in buffer)
        private val AP1_BASE  = 0
        private val AP2_BASE  = AP1_BASE  + AP1_LEN + 1
        private val AP3_BASE  = AP2_BASE  + AP2_LEN + 1
        private val AP4_BASE  = AP3_BASE  + AP3_LEN + 1
        private val DAP1A_BASE = AP4_BASE + AP4_LEN + 1
        private val DAP1B_BASE = DAP1A_BASE + DAP1A_LEN + 1
        private val DEL1_BASE  = DAP1B_BASE + DAP1B_LEN + 1
        private val DAP2A_BASE = DEL1_BASE + DEL1_LEN + 1
        private val DAP2B_BASE = DAP2A_BASE + DAP2A_LEN + 1
        private val DEL2_BASE  = DAP2B_BASE + DAP2B_LEN + 1
        private val TOTAL_MEMORY = DEL2_BASE + DEL2_LEN + 1

        // LFO tap offsets (scaled)
        private val DEL2_TAP = (6261 * RATE_RATIO)
        private val DEL2_LFO_AMP = (50 * RATE_RATIO)
        private val DEL1_TAP = (4460 * RATE_RATIO)
        private val DEL1_LFO_AMP = (40 * RATE_RATIO)
    }

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
        // LFO frequencies scaled for 44100Hz
        lfo1.initApproximate(0.5f / 44100f * 32f)
        lfo2.initApproximate(0.3f / 44100f * 32f)
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
        // AP1
        val ap1Tail = readBuffer(AP1_BASE + AP1_LEN - 1)
        prevRead = ap1Tail
        acc += ap1Tail * kap
        writeBuffer(AP1_BASE, acc)
        acc *= -kap
        acc += prevRead

        // AP2
        val ap2Tail = readBuffer(AP2_BASE + AP2_LEN - 1)
        prevRead = ap2Tail
        acc += ap2Tail * kap
        writeBuffer(AP2_BASE, acc)
        acc *= -kap
        acc += prevRead

        // AP3
        val ap3Tail = readBuffer(AP3_BASE + AP3_LEN - 1)
        prevRead = ap3Tail
        acc += ap3Tail * kap
        writeBuffer(AP3_BASE, acc)
        acc *= -kap
        acc += prevRead

        // AP4
        val ap4Tail = readBuffer(AP4_BASE + AP4_LEN - 1)
        prevRead = ap4Tail
        acc += ap4Tail * kap
        writeBuffer(AP4_BASE, acc)
        acc *= -kap
        acc += prevRead

        val apout = acc

        // ---- Main reverb loop: Path 1 (left output) ----
        acc = apout
        // Interpolate del2 with LFO modulation
        acc += interpolate(DEL2_BASE, DEL2_TAP, lfoValue1, DEL2_LFO_AMP) * krt
        // LP filter
        lpDecay1 += klp * (acc - lpDecay1)
        acc = lpDecay1
        // DAP1A
        val dap1aTail = readBuffer(DAP1A_BASE + DAP1A_LEN - 1)
        prevRead = dap1aTail
        acc += dap1aTail * (-kap)
        writeBuffer(DAP1A_BASE, acc)
        acc *= kap
        acc += prevRead
        // DAP1B
        val dap1bTail = readBuffer(DAP1B_BASE + DAP1B_LEN - 1)
        prevRead = dap1bTail
        acc += dap1bTail * kap
        writeBuffer(DAP1B_BASE, acc)
        acc *= (-kap)
        acc += prevRead
        // Write to DEL1, then scale for wet output (matches MI: c.Write(del1, 2.0f))
        writeBuffer(DEL1_BASE, acc)
        val wetLeft = acc * 2f

        // ---- Main reverb loop: Path 2 (right output) ----
        acc = apout
        // Interpolate del1 with LFO modulation
        acc += interpolate(DEL1_BASE, DEL1_TAP, lfoValue0, DEL1_LFO_AMP) * krt
        // LP filter
        lpDecay2 += klp * (acc - lpDecay2)
        acc = lpDecay2
        // DAP2A
        val dap2aTail = readBuffer(DAP2A_BASE + DAP2A_LEN - 1)
        prevRead = dap2aTail
        acc += dap2aTail * kap
        writeBuffer(DAP2A_BASE, acc)
        acc *= (-kap)
        acc += prevRead
        // DAP2B
        val dap2bTail = readBuffer(DAP2B_BASE + DAP2B_LEN - 1)
        prevRead = dap2bTail
        acc += dap2bTail * (-kap)
        writeBuffer(DAP2B_BASE, acc)
        acc *= kap
        acc += prevRead
        // Write to DEL2, then scale for wet output (matches MI: c.Write(del2, 2.0f))
        writeBuffer(DEL2_BASE, acc)
        val wetRight = acc * 2f

        // ---- Output wet-only (parallel send — dry signal handled externally) ----
        outLeft = wetLeft * amount
        outRight = wetRight * amount
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

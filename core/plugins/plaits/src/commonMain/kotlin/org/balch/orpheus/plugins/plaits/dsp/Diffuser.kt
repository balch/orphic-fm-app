// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/fx/diffuser.h
// Simplified: FloatArray buffer instead of 12-bit compression.

package org.balch.orpheus.plugins.plaits.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Granular diffuser effect: allpass delay network with LFO modulation.
 * Uses 7 delay lines totaling ~8093 samples.
 * Ported from plaits Diffuser (FxEngine-based).
 */
class Diffuser {
    // Delay line buffer (all delay lines share one contiguous buffer)
    private val buffer = FloatArray(TOTAL_BUFFER_SIZE)
    private var writePos = 0

    // Delay line start offsets within the buffer
    // ap1=126, ap2=180, ap3=269, ap4=444, dapa=1653, dapb=2010, del=3411
    private val ap1Start = 0
    private val ap2Start = 126
    private val ap3Start = 126 + 180
    private val ap4Start = 126 + 180 + 269
    private val dapaStart = 126 + 180 + 269 + 444
    private val dapbStart = 126 + 180 + 269 + 444 + 1653
    private val delStart = 126 + 180 + 269 + 444 + 1653 + 2010

    // LFO state (cosine oscillator)
    private var lfoPhase = 0f
    private val lfoFreq = 0.3f / 44100.0f // scaled from 48kHz original

    // LP filter state
    private var lpDecay = 0.0f

    fun init() {
        buffer.fill(0f)
        writePos = 0
        lfoPhase = 0f
        lpDecay = 0.0f
    }

    fun reset() {
        buffer.fill(0f)
    }

    fun process(amount: Float, rt: Float, inOut: FloatArray, size: Int) {
        val kap = 0.625f
        val klp = 0.75f
        var lp = lpDecay

        for (i in 0 until size) {
            // Advance LFO
            lfoPhase += lfoFreq
            if (lfoPhase >= 1.0f) lfoPhase -= 1.0f
            val lfo = sin(2.0f * PI.toFloat() * lfoPhase)

            // Write input to current position
            var acc = inOut[i]

            // ap1: allpass (length 126)
            acc = processAllpass(acc, ap1Start, 126, 125, kap)

            // ap2: allpass (length 180)
            acc = processAllpass(acc, ap2Start, 180, 179, kap)

            // ap3: allpass (length 269)
            acc = processAllpass(acc, ap3Start, 269, 268, kap)

            // ap4: allpass with LFO modulation (length 444)
            // Interpolate: center=400, depth=43 modulated by LFO
            val ap4Tap = 400.0f + 43.0f * lfo
            acc = processAllpassInterp(acc, ap4Start, 444, ap4Tap, kap)

            // del: delay with LFO modulation + feedback (length 3411)
            // Interpolate: center=3070, depth=340 modulated by LFO
            val delTap = 3070.0f + 340.0f * lfo
            val delSample = readInterp(delStart, 3411, delTap)
            acc += delSample * rt

            // LP filter
            lp += klp * (acc - lp)
            acc = lp

            // dapa: allpass (length 1653)
            val dapaTail = readDelay(dapaStart, 1653, 1652)
            val dapaIn = acc + dapaTail * (-kap) // note: negated kap for dapa
            writeDelay(dapaStart, 1653, dapaIn)
            acc = dapaTail + dapaIn * kap

            // dapb: allpass (length 2010)
            acc = processAllpass(acc, dapbStart, 2010, 2009, kap)

            // Write to del
            writeDelay(delStart, 3411, acc * 2.0f)

            val wet = acc
            inOut[i] += amount * (wet - inOut[i])

            // Advance write position
            writePos++
            if (writePos >= TOTAL_BUFFER_SIZE) writePos = 0
        }
        lpDecay = lp
    }

    private fun processAllpass(input: Float, start: Int, length: Int, tapSample: Int, kap: Float): Float {
        val tail = readDelay(start, length, tapSample)
        val toWrite = input + tail * kap
        writeDelay(start, length, toWrite)
        return tail + toWrite * (-kap)
    }

    private fun processAllpassInterp(input: Float, start: Int, length: Int, tap: Float, kap: Float): Float {
        val tail = readInterp(start, length, tap)
        val toWrite = input + tail * kap
        writeDelay(start, length, toWrite)
        return tail + toWrite * (-kap)
    }

    private fun readDelay(start: Int, length: Int, delaySamples: Int): Float {
        var idx = writePos - delaySamples
        while (idx < 0) idx += TOTAL_BUFFER_SIZE
        idx %= TOTAL_BUFFER_SIZE
        // Map to buffer position relative to delay line start
        val bufIdx = (start + (idx % length)) % TOTAL_BUFFER_SIZE
        return buffer[bufIdx]
    }

    private fun readInterp(start: Int, length: Int, delaySamples: Float): Float {
        val intDelay = delaySamples.toInt()
        val frac = delaySamples - intDelay
        val a = readDelay(start, length, intDelay)
        val b = readDelay(start, length, intDelay + 1)
        return a + (b - a) * frac
    }

    private fun writeDelay(start: Int, length: Int, value: Float) {
        val bufIdx = (start + (writePos % length)) % TOTAL_BUFFER_SIZE
        buffer[bufIdx] = value
    }

    companion object {
        // Total buffer: 126 + 180 + 269 + 444 + 1653 + 2010 + 3411 = 8093
        private const val TOTAL_BUFFER_SIZE = 8093
    }
}

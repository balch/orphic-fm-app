// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/swarm_engine.h + swarm_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.dsp.FastSineOscillator
import org.balch.orpheus.plugins.plaits.dsp.SineOscillator

private const val NUM_SWARM_VOICES = 8
private const val MAX_FREQUENCY = 0.25f

// ═══════════════════════════════════════════════════════════
// GrainEnvelope
// ═══════════════════════════════════════════════════════════

/**
 * Phase accumulator with random frequency/amplitude modulation for grain clouds.
 * Ported from plaits GrainEnvelope.
 */
private class GrainEnvelope {
    private var from = 0.0f
    private var interval = 1.0f
    private var phase = 1.0f
    private var fm = 0.0f
    private var amplitude = 0.5f
    private var previousSizeRatio = 0.0f
    private var filterCoefficient = 0.0f
    private val random = PlaitsDsp.Random()

    fun init() {
        from = 0.0f
        interval = 1.0f
        phase = 1.0f
        fm = 0.0f
        amplitude = 0.5f
        previousSizeRatio = 0.0f
        filterCoefficient = 0.0f
    }

    fun step(rate: Float, burstMode: Boolean, startBurst: Boolean) {
        var randomize = false
        if (startBurst) {
            phase = 0.5f
            fm = 16.0f
            randomize = true
        } else {
            phase += rate * fm
            if (phase >= 1.0f) {
                phase -= phase.toInt().toFloat()
                randomize = true
            }
        }

        if (randomize) {
            from += interval
            interval = random.getFloat() - from
            fm = if (burstMode) {
                fm * (0.8f + 0.2f * random.getFloat())
            } else {
                0.5f + 1.5f * random.getFloat()
            }
        }
    }

    fun frequency(sizeRatio: Float): Float {
        return if (sizeRatio < 1.0f) {
            2.0f * (from + interval * phase) - 1.0f
        } else {
            from
        }
    }

    fun amplitude(sizeRatio: Float): Float {
        var targetAmplitude = 1.0f
        if (sizeRatio >= 1.0f) {
            var p = (phase - 0.5f) * sizeRatio
            p = p.coerceIn(-1.0f, 1.0f)
            val e = SineOscillator.sine(0.5f * p + 1.25f)
            targetAmplitude = 0.5f * (e + 1.0f)
        }

        if ((sizeRatio >= 1.0f) xor (previousSizeRatio >= 1.0f)) {
            filterCoefficient = 0.5f
        }
        filterCoefficient *= 0.95f

        previousSizeRatio = sizeRatio
        // ONE_POLE
        amplitude += (0.5f - filterCoefficient) * (targetAmplitude - amplitude)
        return amplitude
    }
}

// ═══════════════════════════════════════════════════════════
// AdditiveSawOscillator
// ═══════════════════════════════════════════════════════════

/**
 * PolyBLEP sawtooth that renders additively into the output buffer.
 * Ported from plaits AdditiveSawOscillator.
 */
private class AdditiveSawOscillator {
    private var phase = 0.0f
    private var nextSample = 0.0f
    private var frequency = 0.01f
    private var gain = 0.0f

    fun init() {
        phase = 0.0f
        nextSample = 0.0f
        frequency = 0.01f
        gain = 0.0f
    }

    fun render(frequency: Float, level: Float, out: FloatArray, size: Int) {
        val freq = frequency.coerceAtMost(MAX_FREQUENCY)
        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, freq, size)
        val gainMod = PlaitsDsp.ParameterInterpolator(this.gain, level, size)
        this.frequency = freq
        this.gain = level

        var ns = nextSample
        var ph = phase

        for (i in 0 until size) {
            var thisSample = ns
            ns = 0.0f

            val f = fm.next()
            ph += f

            if (ph >= 1.0f) {
                ph -= 1.0f
                val t = ph / f
                thisSample -= PlaitsDsp.thisBlepSample(t)
                ns -= PlaitsDsp.nextBlepSample(t)
            }

            ns += ph
            out[i] += (2.0f * thisSample - 1.0f) * gainMod.next()
        }
        phase = ph
        nextSample = ns
    }
}

// ═══════════════════════════════════════════════════════════
// SwarmVoice
// ═══════════════════════════════════════════════════════════

/**
 * Single swarm voice: grain envelope + sawtooth + sine oscillator.
 * Ported from plaits SwarmVoice.
 */
private class SwarmVoice {
    private var rank = 0.0f
    private val envelope = GrainEnvelope()
    private val saw = AdditiveSawOscillator()
    private val sine = FastSineOscillator()

    fun init(rank: Float) {
        this.rank = rank
        envelope.init()
        saw.init()
        sine.init()
    }

    fun render(
        f0: Float,
        density: Float,
        burstMode: Boolean,
        startBurst: Boolean,
        spread: Float,
        sizeRatio: Float,
        sawOut: FloatArray,
        sineOut: FloatArray,
        size: Int
    ) {
        envelope.step(density, burstMode, startBurst)

        val scale = 1.0f / NUM_SWARM_VOICES
        val amplitude = envelope.amplitude(sizeRatio) * scale

        val expoAmount = envelope.frequency(sizeRatio)
        var freq = f0 * PlaitsDsp.semitonesToRatio(48.0f * expoAmount * spread * rank)

        val linearAmount = rank * (rank + 0.01f) * spread * 0.25f
        freq *= 1.0f + linearAmount

        saw.render(freq, amplitude, sawOut, size)
        sine.render(freq, amplitude, sineOut, size)
    }
}

// ═══════════════════════════════════════════════════════════
// SwarmEngine
// ═══════════════════════════════════════════════════════════

/**
 * Swarm of 8 sawtooth/sine voice pairs with grain envelope modulation.
 * Main output (out) = sines, aux output = sawtooths.
 *
 * Parameter mapping:
 * - note → base pitch
 * - timbre → density (grain triggering rate)
 * - morph → grain size (low = continuous, high = granular)
 * - harmonics → spread (frequency spread between voices)
 */
class SwarmEngine : PlaitsEngine {
    override val id = PlaitsEngineId.SWARM
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val voices = Array(NUM_SWARM_VOICES) { SwarmVoice() }

    override fun init() {
        reset()
    }

    override fun reset() {
        val n = (NUM_SWARM_VOICES - 1).toFloat() / 2.0f
        for (i in 0 until NUM_SWARM_VOICES) {
            val rank = (i.toFloat() - n) / n
            voices[i].init(rank)
        }
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)
        val controlRate = size.toFloat()
        val density = PlaitsDsp.noteToFrequency(params.timbre * 120.0f) * 0.025f * controlRate
        val spread = params.harmonics * params.harmonics * params.harmonics
        var sizeRatio = 0.25f * PlaitsDsp.semitonesToRatio((1.0f - params.morph) * 84.0f)

        val burstMode = (params.trigger.bits and 2) == 0  // not UNPATCHED
        val startBurst = (params.trigger.bits and 1) != 0

        // Zero output buffers
        for (i in 0 until size) out[i] = 0f
        val auxBuf = aux ?: FloatArray(size)
        for (i in 0 until size) auxBuf[i] = 0f

        for (voice in voices) {
            voice.render(f0, density, burstMode, startBurst, spread, sizeRatio, auxBuf, out, size)
            sizeRatio *= 0.97f
        }

        // Copy aux back if needed
        if (aux != null && aux !== auxBuf) {
            auxBuf.copyInto(aux, 0, 0, size)
        }

        return false
    }
}

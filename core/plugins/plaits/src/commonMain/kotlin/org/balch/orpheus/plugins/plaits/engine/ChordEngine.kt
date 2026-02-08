// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/chord_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.PlaitsWavetables
import org.balch.orpheus.plugins.plaits.dsp.CHORD_NUM_VOICES
import org.balch.orpheus.plugins.plaits.dsp.ChordBank
import org.balch.orpheus.plugins.plaits.dsp.StringSynthOscillator
import org.balch.orpheus.plugins.plaits.dsp.WavetableOscillator
import kotlin.math.max

private const val CHORD_NUM_HARMONICS = 3
private const val REGISTRATION_TABLE_SIZE = 8

/**
 * Chord engine: wavetable oscillators + divide-down organ, with chord quantization.
 *
 * Parameter mapping:
 * - note → root pitch
 * - timbre → chord inversion/voicing
 * - morph → registration blend (low = organ, mid = crossfade, high = wavetable)
 * - harmonics → chord type selection (11 chords)
 */
class ChordEngine : PlaitsEngine {
    override val id = PlaitsEngineId.CHORD
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.3f

    private val divideDownVoice = Array(CHORD_NUM_VOICES) { StringSynthOscillator() }
    private val wavetableVoice = Array(CHORD_NUM_VOICES) { WavetableOscillator() }
    private val chords = ChordBank()

    private var morphLp = 0.0f
    private var timbreLp = 0.0f

    override fun init() {
        for (i in 0 until CHORD_NUM_VOICES) {
            divideDownVoice[i].init()
            wavetableVoice[i].init()
        }
        chords.init()
        chords.reset()
        morphLp = 0.0f
        timbreLp = 0.0f
    }

    override fun reset() {
        chords.reset()
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        // ONE_POLE smooth parameters
        morphLp += 0.1f * (params.morph - morphLp)
        timbreLp += 0.1f * (params.timbre - timbreLp)

        chords.setChord(params.harmonics)

        val harmonics = FloatArray(CHORD_NUM_HARMONICS * 2 + 2)
        val noteAmplitudes = FloatArray(CHORD_NUM_VOICES)
        val registration = max(1.0f - morphLp * 2.15f, 0.0f)

        computeRegistration(registration, harmonics)
        harmonics[CHORD_NUM_HARMONICS * 2] = 0.0f

        val ratios = FloatArray(CHORD_NUM_VOICES)
        val auxNoteMask = chords.computeChordInversion(timbreLp, ratios, noteAmplitudes)

        // Zero output buffers
        for (i in 0 until size) out[i] = 0f
        val auxBuf = aux ?: FloatArray(size)
        for (i in 0 until size) auxBuf[i] = 0f

        val f0 = PlaitsDsp.noteToFrequency(params.note) * 0.998f
        val waveformParam = max((morphLp - 0.535f) * 2.15f, 0.0f)

        for (note in 0 until CHORD_NUM_VOICES) {
            var wavetableAmount = 50.0f * (morphLp - FADE_POINT[note])
            wavetableAmount = wavetableAmount.coerceIn(0.0f, 1.0f)

            var divideDownAmount = 1.0f - wavetableAmount
            val destination = if ((1 shl note) and auxNoteMask != 0) auxBuf else out

            val noteF0 = f0 * ratios[note]
            var divideDownGain = 4.0f - noteF0 * 32.0f
            divideDownGain = divideDownGain.coerceIn(0.0f, 1.0f)
            divideDownAmount *= divideDownGain

            if (wavetableAmount > 0f) {
                wavetableVoice[note].render(
                    noteF0 * 1.004f,
                    noteAmplitudes[note] * wavetableAmount,
                    waveformParam,
                    PlaitsWavetables.CHORD_WAVETABLE_OFFSETS,
                    destination,
                    size
                )
            }

            if (divideDownAmount > 0f) {
                divideDownVoice[note].render(
                    noteF0,
                    harmonics,
                    noteAmplitudes[note] * divideDownAmount,
                    destination,
                    size
                )
            }
        }

        // Mix: out += aux, aux *= 3
        for (i in 0 until size) {
            out[i] += auxBuf[i]
            auxBuf[i] *= 3.0f
        }

        // Copy aux back if needed
        if (aux != null && aux !== auxBuf) {
            auxBuf.copyInto(aux, 0, 0, size)
        }

        return false
    }

    private fun computeRegistration(registration: Float, amplitudes: FloatArray) {
        val reg = registration * (REGISTRATION_TABLE_SIZE - 1.001f)
        val regIntegral = reg.toInt()
        val regFractional = reg - regIntegral

        for (i in 0 until CHORD_NUM_HARMONICS * 2) {
            val a = REGISTRATIONS[regIntegral][i]
            val b = REGISTRATIONS[regIntegral + 1][i]
            amplitudes[i] = a + (b - a) * regFractional
        }
    }

    companion object {
        /** Morph crossfade transition points per voice. */
        private val FADE_POINT = floatArrayOf(0.55f, 0.47f, 0.49f, 0.51f, 0.53f)

        /** 8 registration presets, each with 6 harmonic amplitudes (3 saw + 3 square). */
        private val REGISTRATIONS = arrayOf(
            floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),       // Square
            floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),       // Saw
            floatArrayOf(0.5f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f),       // Saw + saw
            floatArrayOf(0.33f, 0.0f, 0.33f, 0.0f, 0.33f, 0.0f),    // Full saw
            floatArrayOf(0.33f, 0.0f, 0.0f, 0.33f, 0.0f, 0.33f),    // Full hybrid
            floatArrayOf(0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f),       // Saw + high sq
            floatArrayOf(0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.5f),       // Square + high sq
            floatArrayOf(0.0f, 0.1f, 0.1f, 0.0f, 0.2f, 0.6f),       // Mixed + high
        )
    }
}

// Copyright 2021 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/chords/chord_bank.h + chord_bank.cc

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

const val CHORD_NUM_NOTES = 4
const val CHORD_NUM_VOICES = CHORD_NUM_NOTES + 1

private const val CHORD_NUM_CHORDS = 11

/**
 * Chord quantizer and inversion calculator.
 * Holds 11 chord types with 4 notes each, plus voice rotation/crossfading.
 * Ported from plaits ChordBank.
 */
class ChordBank {
    private val chordIndexQuantizer = HysteresisQuantizer2()
    private val ratios = FloatArray(CHORD_NUM_CHORDS * CHORD_NUM_NOTES)
    private val noteCount = IntArray(CHORD_NUM_CHORDS)
    private val sortedRatios = FloatArray(CHORD_NUM_NOTES)

    fun init() {
        chordIndexQuantizer.init(CHORD_NUM_CHORDS, 0.075f, false)
    }

    fun reset() {
        for (i in 0 until CHORD_NUM_CHORDS) {
            var count = 0
            for (j in 0 until CHORD_NUM_NOTES) {
                val semitones = CHORDS[i][j]
                ratios[i * CHORD_NUM_NOTES + j] = PlaitsDsp.semitonesToRatio(semitones)
                if (semitones != 0.01f && semitones != 7.01f &&
                    semitones != 11.99f && semitones != 12.00f
                ) {
                    count++
                }
            }
            noteCount[i] = count
        }
        sort()
    }

    fun setChord(parameter: Float) {
        chordIndexQuantizer.process(parameter * 1.02f)
    }

    private fun chordIndex(): Int = chordIndexQuantizer.quantizedValue()

    private fun ratio(note: Int): Float =
        ratios[chordIndex() * CHORD_NUM_NOTES + note]

    private fun sort() {
        for (i in 0 until CHORD_NUM_NOTES) {
            var r = ratio(i)
            while (r > 2.0f) r *= 0.5f
            sortedRatios[i] = r
        }
        sortedRatios.sort()
    }

    /**
     * Compute chord voicing with smooth inversion crossfading.
     * @param inversion Inversion amount (0..1 mapped from timbre)
     * @param outRatios Output frequency ratios for [CHORD_NUM_VOICES] voices
     * @param amplitudes Output amplitudes for [CHORD_NUM_VOICES] voices
     * @return Bitmask of voices that go to aux output
     */
    fun computeChordInversion(
        inversion: Float,
        outRatios: FloatArray,
        amplitudes: FloatArray
    ): Int {
        val baseRatioOffset = chordIndex() * CHORD_NUM_NOTES
        val inv = inversion * (CHORD_NUM_NOTES * CHORD_NUM_VOICES).toFloat()

        val invIntegral = inv.toInt()
        val invFractional = inv - invIntegral

        val numRotations = invIntegral / CHORD_NUM_NOTES
        val rotatedNote = invIntegral % CHORD_NUM_NOTES

        val kBaseGain = 0.25f
        var mask = 0

        for (i in 0 until CHORD_NUM_NOTES) {
            val transposition = 0.25f *
                (1 shl ((CHORD_NUM_NOTES - 1 + invIntegral - i) / CHORD_NUM_NOTES)).toFloat()
            val targetVoice = (i - numRotations + CHORD_NUM_VOICES) % CHORD_NUM_VOICES
            val previousVoice = (targetVoice - 1 + CHORD_NUM_VOICES) % CHORD_NUM_VOICES

            if (i == rotatedNote) {
                outRatios[targetVoice] = ratios[baseRatioOffset + i] * transposition
                outRatios[previousVoice] = outRatios[targetVoice] * 2.0f
                amplitudes[previousVoice] = kBaseGain * invFractional
                amplitudes[targetVoice] = kBaseGain * (1.0f - invFractional)
            } else if (i < rotatedNote) {
                outRatios[previousVoice] = ratios[baseRatioOffset + i] * transposition
                amplitudes[previousVoice] = kBaseGain
            } else {
                outRatios[targetVoice] = ratios[baseRatioOffset + i] * transposition
                amplitudes[targetVoice] = kBaseGain
            }

            if (i == 0) {
                if (i >= rotatedNote) mask = mask or (1 shl targetVoice)
                if (i <= rotatedNote) mask = mask or (1 shl previousVoice)
            }
        }
        return mask
    }

    companion object {
        /** 11 chord types, 4 notes each (semitone intervals from root). */
        private val CHORDS = arrayOf(
            floatArrayOf(0.00f, 0.01f, 11.99f, 12.00f),  // OCT
            floatArrayOf(0.00f, 7.00f, 7.01f, 12.00f),   // 5
            floatArrayOf(0.00f, 5.00f, 7.00f, 12.00f),   // sus4
            floatArrayOf(0.00f, 3.00f, 7.00f, 12.00f),   // m
            floatArrayOf(0.00f, 3.00f, 7.00f, 10.00f),   // m7
            floatArrayOf(0.00f, 3.00f, 10.00f, 14.00f),  // m9
            floatArrayOf(0.00f, 3.00f, 10.00f, 17.00f),  // m11
            floatArrayOf(0.00f, 2.00f, 9.00f, 16.00f),   // 69
            floatArrayOf(0.00f, 4.00f, 11.00f, 14.00f),  // M9
            floatArrayOf(0.00f, 4.00f, 7.00f, 11.00f),   // M7
            floatArrayOf(0.00f, 4.00f, 7.00f, 12.00f),   // M
        )
    }
}

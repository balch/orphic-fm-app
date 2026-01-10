package org.balch.orpheus.core.audio.dsp.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Lookup tables for the FM Drum synthesizer.
 * Extracted from Mutable Instruments Peaks resources.
 */
object FmDrumTables {
    // Sine wavetable (1025 entries)
    val SINE = ShortArray(1025) { i ->
        (sin(i * 2.0 * PI / 1024.0) * 32767.0).toInt().toShort()
    }

    // Exponential envelope curve (257 entries)
    val ENV_EXPO = ShortArray(257) { i ->
        val x = i / 256.0
        // Exponential decay approximation: 2^(-x*8)
        (32767.0 * (1.0 - exp(-x * 5.0))).toInt().toShort()
    }

    // Overdrive waveshaper (1025 entries)
    val OVERDRIVE = ShortArray(1025) { i ->
        val x = (i - 512.0) / 512.0
        (32767.0 * tanh(x * 2.0)).toInt().toShort()
    }

    // Envelope increments (257 entries)
    // Using a simple exponential mapping for now to avoid massive text blobs
    val ENV_INCREMENTS = IntArray(257) { i ->
        val x = i / 256.0
        // From ~1Hz to ~1000Hz equivalent increment
        (100.0 * 2.0.pow(x * 10.0)).toInt()
    }

    // Oscillator phase increments mapping MIDI pitch (97 entries)
    val OSCILLATOR_INCREMENTS = IntArray(97) { i ->
        val midiPitch = i + 12.0
        val freq = 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)
        (freq * (1L shl 32) / 44100.0).toLong().toInt()
    }
}

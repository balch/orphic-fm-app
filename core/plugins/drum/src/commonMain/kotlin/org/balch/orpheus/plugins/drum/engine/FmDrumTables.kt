package org.balch.orpheus.plugins.drum.engine

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
    // Maps decay parameter (0-65535, indexed by >>8) to phase increment per sample.
    // Full 32-bit phase space (0 → 0xFFFFFFFF) must be traversed in ~5ms (fast) to ~2s (slow).
    // At 44100 Hz: 5ms = 220 samples → increment ≈ 19.5M, 2s = 88200 samples → increment ≈ 48.7K
    val ENV_INCREMENTS = IntArray(257) { i ->
        val x = i / 256.0
        // Exponential mapping from fast (short) to slow (long) decay
        // i=0 → slowest (~2s), i=256 → fastest (~5ms)
        val durationSamples = 88200.0 * 2.0.pow(-x * 4.5) // ~2s down to ~4ms
        (4294967295.0 / durationSamples).toLong().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    // Oscillator phase increments mapping MIDI pitch (97 entries)
    val OSCILLATOR_INCREMENTS = IntArray(97) { i ->
        val midiPitch = i + 12.0
        val freq = 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)
        (freq * (1L shl 32) / 44100.0).toLong().toInt()
    }
}

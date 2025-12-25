package org.balch.orpheus.core.audio.dsp

/**
 * Two-input multiplier: output = inputA * inputB
 */
expect interface Multiply : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
}

/**
 * Two-input adder: output = inputA + inputB
 */
expect interface Add : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
}

/**
 * Multiply-Add: output = (inputA * inputB) + inputC
 */
expect interface MultiplyAdd : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    val inputC: AudioInput
}

/**
 * Pass-through unit (useful for signal splitting/distribution)
 */
expect interface PassThrough : AudioUnit {
    val input: AudioInput
}

/**
 * Sine oscillator (for LFOs and modulation)
 */
expect interface SineOscillator : AudioUnit {
    val frequency: AudioInput
    val amplitude: AudioInput
}

/**
 * Triangle oscillator
 */
expect interface TriangleOscillator : AudioUnit {
    val frequency: AudioInput
    val amplitude: AudioInput
}

/**
 * Square oscillator
 */
expect interface SquareOscillator : AudioUnit {
    val frequency: AudioInput
    val amplitude: AudioInput
}

/**
 * Minimum: output = min(inputA, inputB)
 */
expect interface Minimum : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
}

/**
 * Maximum: output = max(inputA, inputB)
 */
expect interface Maximum : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
}

package org.balch.orpheus.plugins.plaits

/**
 * Universal synthesis engine interface modeled after Mutable Instruments Plaits.
 *
 * Each engine renders audio blocks given a set of universal parameters
 * (note, timbre, morph, harmonics, accent) plus a trigger state.
 * Engines are stateful and maintain their own internal DSP state.
 */
interface PlaitsEngine {
    val id: PlaitsEngineId
    val displayName: String

    /**
     * When true, the engine produces its own amplitude envelope
     * (e.g. drum engines). The host should bypass its external VCA envelope.
     */
    val alreadyEnveloped: Boolean

    /**
     * Output gain scaling. Negative values indicate a limiter should be used.
     */
    val outGain: Float get() = 1.0f

    /**
     * Initialize engine state and allocate buffers.
     */
    fun init()

    /**
     * Reset engine to initial state (silence).
     */
    fun reset()

    /**
     * Render a block of audio samples.
     *
     * @param params Universal engine parameters for this block
     * @param out Output buffer (main output), must have at least [size] capacity
     * @param aux Optional auxiliary output buffer, may be null
     * @param size Number of samples to render
     * @return true if the output is already enveloped for this specific render call
     */
    fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean
}

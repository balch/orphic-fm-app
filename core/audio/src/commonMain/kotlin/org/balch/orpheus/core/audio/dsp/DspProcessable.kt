package org.balch.orpheus.core.audio.dsp

/**
 * Interface for units that participate in the DSP render graph.
 */
interface DspProcessable {
    fun process(numFrames: Int)
    fun allocateBuffers(maxFrames: Int)
    fun getInputPorts(): List<DspAudioInput>
    fun getOutputPorts(): List<DspAudioOutput> = emptyList()

    /** When false, the scheduler skips process() and zeros output buffers.
     *  Thread-safe: written from UI thread, read from audio thread.
     *  Implementors on JVM/Android should annotate their backing field with
     *  `@kotlin.concurrent.Volatile` for cross-thread visibility.
     *  On WASM (single-threaded), no annotation is needed. */
    var enabled: Boolean
        get() = true
        set(_) {}
}

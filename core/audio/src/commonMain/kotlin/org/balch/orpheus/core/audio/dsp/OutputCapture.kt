package org.balch.orpheus.core.audio.dsp

/** Taps the engine's final stereo output for recording. Desktop and Android. */
interface OutputCapture {
    val sampleRate: Int

    /** False once the platform stopped feeding the take, e.g. an Android route change. */
    val isCapturing: Boolean get() = true
    fun start()
    fun stop()

    /** Copies up to into.size / 2 interleaved stereo frames; returns frames copied. */
    fun drain(into: FloatArray): Int
    fun droppedFrames(): Long
}

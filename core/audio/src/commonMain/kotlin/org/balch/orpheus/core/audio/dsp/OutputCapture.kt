package org.balch.orpheus.core.audio.dsp

/** Taps the engine's final stereo output for recording. Desktop only. */
interface OutputCapture {
    val sampleRate: Int
    fun start()
    fun stop()

    /** Copies up to into.size / 2 interleaved stereo frames; returns frames copied. */
    fun drain(into: FloatArray): Int
    fun droppedFrames(): Long
}

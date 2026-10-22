package org.balch.orpheus.core.audio.dsp

/** The engine's per-block mix bench summaries ([BenchSummaryLayout]). Desktop only for now. */
interface MixBenchTap {
    /** Turns the native taps on or off. Turning off may block up to ~100 ms. */
    fun setEnabled(on: Boolean)

    /** Copies up to out.size / [BenchSummaryLayout.FLOATS] summaries, oldest first; returns the count. One reader only. */
    fun drain(out: FloatArray): Int
}

package org.balch.orpheus.core.audio.dsp

/** The engine's per-block mix bench summaries ([BenchSummaryLayout]). Desktop and Android; iOS has none. */
interface MixBenchTap {
    /** Turns the native taps on or off. Turning off may block up to ~100 ms. */
    fun setEnabled(on: Boolean)

    /** Copies up to out.size / [BenchSummaryLayout.FLOATS] summaries, oldest first; returns the count. One reader only. */
    fun drain(out: FloatArray): Int
}

/** For engines without the native taps: edits still work, the meters stay silent. */
object NoMeterTap : MixBenchTap {
    override fun setEnabled(on: Boolean) {}

    override fun drain(out: FloatArray): Int = 0
}

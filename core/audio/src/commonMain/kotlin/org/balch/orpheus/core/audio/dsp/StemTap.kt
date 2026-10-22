package org.balch.orpheus.core.audio.dsp

/** Raw per-stem audio from the engine's stem ring. Desktop and Android; iOS has none. */
interface StemTap {
    /** Allocates or frees the native ring; false when the native library lacks it. */
    fun setEnabled(on: Boolean): Boolean

    /**
     * Starts recording [frames] frames; false when not enabled or already armed.
     * It records only while the mix bench (metering) is on, so it can return true yet record nothing.
     */
    fun arm(frames: Int): Boolean

    /** Up to out.size / [StemLayout.FLOATS_PER_FRAME] frames, interleaved per [StemLayout], oldest first; one reader. */
    fun drain(out: FloatArray): Int

    /** Frames still to record; 0 when done or off. */
    fun remaining(): Int

    /** Frames dropped since enable. */
    fun dropped(): Long
}

/** An engine that carries a [StemTap] beside its other taps. */
interface StemTapSource {
    val stemTap: StemTap
}

/** For engines without the native ring. */
object NoStemTap : StemTap {
    override fun setEnabled(on: Boolean): Boolean = false
    override fun arm(frames: Int): Boolean = false
    override fun drain(out: FloatArray): Int = 0
    override fun remaining(): Int = 0
    override fun dropped(): Long = 0L
}

/** Frame layout of the stem ring, mirrored from orpheus_dsp.h (guarded by StemLayoutTest). */
object StemLayout {
    const val STEMS = 13
    const val FLOATS_PER_FRAME = 26

    /** Stem order within a frame; each stem is an L then R float. */
    val NAMES: List<String> =
        (0..7).map { "track$it" } + listOf("storm", "street", "speech", "bus", "out")
}

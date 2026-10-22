package org.balch.orpheus.features.pulsar.bench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.audio.dsp.StemTap

/**
 * An in-memory stem ring with the native semantics: [render] plays the audio thread, recording only while
 * enabled, armed and [gate] (the mix bench) is on. Every float of frame k holds k, counted from enable.
 */
class FakeStemTap(
    private val capacity: Int = 1 shl 16,
    var gate: () -> Boolean = { true },
) : StemTap {
    /** False plays a native library without the stem ring. */
    var libraryHasRing = true
    @Volatile var enabled = false
        private set
    /** Every [setEnabled] argument, in order. */
    val enableCalls = mutableListOf<Boolean>()
    /** Every [arm] request, in order. */
    val arms = mutableListOf<Int>()
    /** The next [render] drops this many of the frames it consumes, as a full ring would. */
    var dropNext = 0
    /** True plays a closed engine: the native pass-throughs read 0 and nothing renders. */
    @Volatile var engineClosed = false

    private val ring = ArrayDeque<Float>()
    private var remaining = 0
    private var dropped = 0L
    private var nextFrame = 0

    @Synchronized
    override fun setEnabled(on: Boolean): Boolean {
        enableCalls += on
        if (!libraryHasRing) return false
        enabled = on
        ring.clear()
        remaining = 0
        dropped = 0
        nextFrame = 0
        return true
    }

    @Synchronized
    override fun arm(frames: Int): Boolean {
        arms += frames
        if (!enabled || remaining > 0) return false
        remaining = frames
        return true
    }

    @Synchronized
    override fun drain(out: FloatArray): Int {
        if (engineClosed) return 0
        val max = minOf(out.size / StemLayout.FLOATS_PER_FRAME, ring.size / StemLayout.FLOATS_PER_FRAME)
        for (i in 0 until max * StemLayout.FLOATS_PER_FRAME) out[i] = ring.removeFirst()
        return max
    }

    @Synchronized
    override fun remaining(): Int = if (engineClosed) 0 else remaining

    @Synchronized
    override fun dropped(): Long = if (engineClosed) 0 else dropped

    /** One audio block of [frames]; consumes remaining by wall-clock frames, drops included. */
    @Synchronized
    fun render(frames: Int) {
        if (engineClosed || !enabled || !gate() || remaining == 0) return
        val take = minOf(frames, remaining)
        val drop = minOf(dropNext, take)
        dropNext -= drop
        dropped += drop
        repeat(take - drop) {
            if (ring.size / StemLayout.FLOATS_PER_FRAME >= capacity) {
                dropped++
            } else {
                val v = nextFrame.toFloat()
                repeat(StemLayout.FLOATS_PER_FRAME) { ring.addLast(v) }
            }
            nextFrame++
        }
        remaining -= take
    }

    /** Renders [frames] every [everyMs] in [scope], like the audio callback. */
    fun renderIn(scope: CoroutineScope, frames: Int = 480, everyMs: Long = 10) = scope.launch {
        while (isActive) {
            render(frames)
            delay(everyMs)
        }
    }
}

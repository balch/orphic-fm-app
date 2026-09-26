package org.balch.orpheus.core.audio.dsp

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.balch.orpheus.core.plugin.viz.ScopeFrame
import kotlin.concurrent.Volatile

/**
 * The [ScopeFrame] the scope poll publishes into. One preallocated array, copied in and out under
 * a lock held for a 256-float copy, so a reader never sees half of one window and half of the next.
 */
internal class ScopeFrameBuffer(
    override val size: Int,
    override val windowMs: Float,
) : ScopeFrame {
    private val lock = SynchronizedObject()
    private val points = FloatArray(size)
    private var triggered = false

    @Volatile
    override var version: Int = 0
        private set

    override fun readInto(dest: FloatArray): Boolean = synchronized(lock) {
        points.copyInto(dest, endIndex = minOf(size, dest.size))
        triggered
    }

    /** Publishes [src] as the latest window. Scope polls call it; two briefly overlapping jobs each publish whole windows. */
    fun publish(src: FloatArray, triggered: Boolean): Unit = synchronized(lock) {
        src.copyInto(points, endIndex = size)
        this.triggered = triggered
        version++
    }
}

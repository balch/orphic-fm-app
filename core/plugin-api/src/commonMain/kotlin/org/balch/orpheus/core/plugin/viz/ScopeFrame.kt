package org.balch.orpheus.core.plugin.viz

/**
 * The latest scope window of the master mix as heard, oldest point first, newest audio last.
 * A frame loop reads it by value: compare [version] with the last one drawn, then copy the points
 * out with [readInto]. Neither allocates, so polling it every frame is free.
 */
interface ScopeFrame {
    /** Points per window. */
    val size: Int

    /** Audio time the window spans, in milliseconds. */
    val windowMs: Float

    /** Bumps with every new window; 0 until the first arrives. */
    val version: Int

    /**
     * Copies the latest window into [dest] (its first `min(size, dest.size)` points) and returns
     * whether it was triggered. An untriggered window (silence or noise) is free-running, so a
     * trace would jitter: hold the last triggered one instead.
     */
    fun readInto(dest: FloatArray): Boolean

    companion object {
        /** For engines with no scope tap: never publishes. */
        val Empty: ScopeFrame = object : ScopeFrame {
            override val size: Int get() = 0
            override val windowMs: Float get() = 0f
            override val version: Int get() = 0
            override fun readInto(dest: FloatArray): Boolean = false
        }
    }
}

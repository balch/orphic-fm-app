package org.balch.orpheus.features.cowbell.shake

import org.balch.orpheus.plugins.cowbell.cowbellStrikeValue

/**
 * One frame's strikes from the fill. Keeps the [MAX_PER_FRAME] loudest and drains them as packed
 * strike words, so a burst of seeds cannot flood the engine. Allocates nothing after construction.
 */
class FillStrikes {
    private val velocity = FloatArray(MAX_PER_FRAME)
    private val kind = IntArray(MAX_PER_FRAME)
    private val color = IntArray(MAX_PER_FRAME)
    private var count = 0

    /** Every strike ever added, kept or not; drains do not reset it. */
    var added: Int = 0
        private set

    fun add(velocity: Float, kind: Int, color: Int) {
        if (velocity.isNaN()) return
        added++
        var slot = count
        if (count < MAX_PER_FRAME) {
            count++
        } else {
            slot = 0
            for (i in 1 until MAX_PER_FRAME) if (this.velocity[i] < this.velocity[slot]) slot = i
            if (velocity <= this.velocity[slot]) return
        }
        this.velocity[slot] = velocity
        this.kind[slot] = kind
        this.color[slot] = color
    }

    /** Sends each kept strike as a packed word, then empties the buffer. */
    fun drain(send: (Float) -> Unit) {
        for (i in 0 until count) send(cowbellStrikeValue(velocity[i], kind[i], color[i]))
        count = 0
    }

    companion object {
        const val MAX_PER_FRAME = 5
    }
}

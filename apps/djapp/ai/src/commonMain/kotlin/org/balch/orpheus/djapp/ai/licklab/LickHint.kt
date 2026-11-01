package org.balch.orpheus.djapp.ai.licklab

import kotlin.math.abs

/** What the next-chord hint points at: the root, the nearest chord tone, or the nearest tone new to the chord. */
enum class HintMode { Root, Near, Move }

/** A triad's root, third and fifth, as scale steps above the chord root. */
internal val CHORD_STEPS = setOf(0, 2, 4)

internal object LickHint {
    /**
     * The index into [keys] of the key to aim for, or null with no next chord or no candidate. [curRoot] and
     * [nextRoot] are chord root degrees (-1 none) in a scale of [scaleSize] steps. Nearest to [handMidi] in
     * semitones wins; a tie goes up.
     */
    fun pick(mode: HintMode, keys: List<StripKey>, curRoot: Int, nextRoot: Int, scaleSize: Int, handMidi: Int): Int? {
        if (nextRoot < 0 || scaleSize <= 0) return null
        fun steps(k: StripKey, root: Int): Int? = k.degree?.let { (it - root).mod(scaleSize) }
        fun tone(k: StripKey, root: Int) = steps(k, root) in CHORD_STEPS
        val near = keys.indices.filter { tone(keys[it], nextRoot) }
        val pool = when (mode) {
            HintMode.Root -> keys.indices.filter { steps(keys[it], nextRoot) == 0 }
            HintMode.Near -> near
            HintMode.Move -> near.filter { curRoot < 0 || !tone(keys[it], curRoot) }.ifEmpty { near }
        }
        return pool.minWithOrNull(
            compareBy<Int> { abs(keys[it].midi - handMidi) }.thenByDescending { keys[it].midi },
        )
    }
}

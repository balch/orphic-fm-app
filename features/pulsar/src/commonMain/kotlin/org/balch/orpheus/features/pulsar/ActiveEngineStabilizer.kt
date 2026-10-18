package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS

/**
 * Debounces the per-track active engine ids so labels do not flicker while the DSP re-rolls the
 * active engine per block in the energy crossfade zone. A new id is committed after
 * [HOLD_FRAMES] consecutive polls of it; a different id restarts the candidate. Not thread safe.
 */
class ActiveEngineStabilizer {
    private val committed = IntArray(PULSAR_NUM_TRACKS) { -1 }
    private val candidate = IntArray(PULSAR_NUM_TRACKS) { -1 }
    private val counter = IntArray(PULSAR_NUM_TRACKS)

    // Re-copied only when a commit happens, so callers can compare by reference.
    private var snapshot: IntArray = committed.copyOf()

    /** Advances one poll of [incoming] (missing entries count as -1) and returns the committed ids. */
    fun advance(incoming: IntArray): IntArray {
        var changed = false
        for (t in committed.indices) {
            val id = incoming.getOrElse(t) { -1 }
            when {
                id == committed[t] -> {
                    candidate[t] = id
                    counter[t] = 0
                }
                id == candidate[t] -> {
                    if (++counter[t] >= HOLD_FRAMES) {
                        committed[t] = id
                        counter[t] = 0
                        changed = true
                    }
                }
                else -> {
                    candidate[t] = id
                    counter[t] = 1
                }
            }
        }
        if (changed) snapshot = committed.copyOf()
        return snapshot
    }

    companion object {
        /** Polls a new id must persist: ceil(400 ms hold / 16 ms viz poll). */
        const val HOLD_FRAMES = 25
    }
}

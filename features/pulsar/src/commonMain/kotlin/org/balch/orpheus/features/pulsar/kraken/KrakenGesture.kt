package org.balch.orpheus.features.pulsar.kraken

/** Targets the engine knows: 0 = IV, 1 = V, 2 = relative, 3 = parallel. */
const val KRAKEN_TARGET_COUNT = 4

/**
 * The Kraken pad's touch logic, apart from Compose and the engine. Hold engages, a second
 * press within [doubleTapMs] of a tap latches, and a press while latched lets go.
 */
class KrakenGesture(
    private val now: () -> Long,
    private val doubleTapMs: Long = DOUBLE_TAP_MS,
) {
    var held = false
        private set
    var latched = false
        private set
    /** Stabs sent to the engine, which edge-detects the count. Never rewinds. */
    var presses = 0
        private set

    val engaged: Boolean get() = held || latched

    private var lastTapAt = NEVER
    private var unlatching = false

    fun press() {
        // A second press with none released is a stray repeat, never a tap.
        if (held) return
        if (latched) {
            latched = false
            unlatching = true
            return
        }
        held = true
        presses++
        if (now() - lastTapAt <= doubleTapMs) latched = true
    }

    fun release() {
        if (unlatching) {
            unlatching = false
            lastTapAt = NEVER
            return
        }
        if (!held) return
        held = false
        lastTapAt = if (latched) NEVER else now()
    }

    fun reset() {
        held = false
        latched = false
        unlatching = false
        lastTapAt = NEVER
    }

    companion object {
        const val DOUBLE_TAP_MS = 300L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}

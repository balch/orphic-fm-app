package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Play this section next" from the UI. The engine holds a request until the current section
 * ends and clears the port itself, so it cannot report one back; this keeps the queued index for
 * display until the requested section arrives or a vibe loads.
 */
class SectionQueue(private val writeRequest: (portValue: Int) -> Unit) {

    private val _queued = MutableStateFlow(NONE)
    val queued: StateFlow<Int> = _queued.asStateFlow()

    /**
     * Queues [index]; the last request wins. Ignored for the playing section (would only
     * repeat), while the outro is armed (a request outranks it), and for the index already
     * queued, since a request for it is already in flight.
     */
    fun request(index: Int, currentSection: Int, sectionCount: Int, outroArmed: Boolean): Boolean {
        if (index !in 0 until sectionCount) return false
        if (!canQueue(index, currentSection, outroArmed)) return false
        if (index == _queued.value) return false
        _queued.value = index
        // The port reads 0 as "no request", so it carries the index plus one.
        writeRequest(index + 1)
        return true
    }

    /** Clears the queue once the requested section is the one playing. */
    fun onSectionObserved(sectionIndex: Int) {
        _queued.compareAndSet(sectionIndex, NONE)
    }

    /** A vibe load resets the engine's arrangement, and any pending request with it. */
    fun clear() {
        _queued.value = NONE
    }

    companion object {
        const val NONE = -1

        /** Shared with the vibe info chips so a tappable chip is always one the queue accepts. */
        fun canQueue(index: Int, currentSection: Int, outroArmed: Boolean): Boolean =
            !outroArmed && index != currentSection
    }
}

package org.balch.orpheus.core.audio

/**
 * Lets the platform audio host be parked while paused and backgrounded, and restarted when the app
 * becomes relevant again. [SynthEngine] extends it; the policy depends on this narrow surface only.
 * Default no-ops: only iOS parks its host.
 */
interface AudioHostSuspender {
    /** Parks the audio host if [stillWanted] is still true when the park actually runs. Non-blocking. */
    fun suspendHost(stillWanted: () -> Boolean) {}
    /** Restarts a parked host unless another app owns audio. Non-blocking. */
    fun resumeHostIfIdle() {}
}

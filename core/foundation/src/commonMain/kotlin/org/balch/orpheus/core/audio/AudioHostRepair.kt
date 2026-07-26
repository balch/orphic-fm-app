package org.balch.orpheus.core.audio

/**
 * Asks the platform audio host to verify it is actually rendering and
 * repair itself if not. Kept separate from [SynthEngine] so playback-policy
 * consumers (PlaybackController's resume path) don't have to depend on the
 * full synth control surface — same rationale as [AudioRouteMonitor].
 *
 * Platform feeds:
 * - iOS: kicks the AVAudioEngine watchdog tick immediately (AVAudioEngine can
 *   stop itself with no app-visible event, e.g. a hardware config change).
 * - Android / Desktop / WASM: no-op — the platform host cannot die out from
 *   under the app the same way, so there is nothing to repair.
 */
interface AudioHostRepair {
    /**
     * Ask the platform audio host to verify it is actually rendering and
     * repair itself if not. Idempotent and non-blocking: safe to call on
     * every play().
     *
     * Exists because a platform host can die without any app-visible event
     * (iOS: AVAudioEngine stops itself on a hardware config change), leaving
     * user intent with no way to recover audio.
     */
    fun ensureAudioHostRunning()
}

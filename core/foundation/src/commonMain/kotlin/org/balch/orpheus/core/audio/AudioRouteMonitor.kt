package org.balch.orpheus.core.audio

import kotlinx.coroutines.flow.SharedFlow

/**
 * Emits when the active audio output device disappears out from under the
 * app — e.g. a Bluetooth speaker powering off. Kept separate from
 * [SynthEngine] so playback-policy consumers (PlaybackController's
 * auto-pause etiquette) don't have to depend on the full synth control
 * surface, and test fakes stay one property big.
 *
 * Platform feeds:
 * - iOS: AVAudioSession route change with reason `oldDeviceUnavailable`.
 * - Android: not yet wired (ACTION_AUDIO_BECOMING_NOISY would feed this).
 * - Desktop / WASM: never emits.
 */
interface AudioRouteMonitor {
    val audioRouteLostFlow: SharedFlow<Unit>
}

package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Easing curve applied to a [MasterVolumeRamp]. Maps wall-clock progress
 * `[0..1]` to fade progress `[0..1]`.
 *
 * - [LINEAR] — constant rate. Sounds abrupt at the very end of a fade-out
 *   because hearing is logarithmic.
 * - [EASE_IN] — slow start, fast end (`t²`). For fade-outs the volume holds
 *   high through most of the duration then drops off — feels like the music
 *   is gracefully bowing out instead of being yanked away.
 * - [EASE_OUT] — fast start, slow tail (`1 - (1-t)²`). Useful for fade-ins
 *   where you want a quick onset.
 * - [LOG] — exponential amplitude / linear-in-dB. Maps t to a -60dB → 0dB
 *   ramp. Sounds even across its entire length on long fades; the only
 *   right choice when durationMs is more than ~1s.
 */
enum class FadeCurve(internal val curve: (Float) -> Float) {
    LINEAR({ it }),
    EASE_IN({ t -> t * t }),
    EASE_OUT({ t -> val u = 1f - t; 1f - u * u }),
    LOG({ t ->
        // Fade progress: curve(0)=0, curve(1)=1. For a fade-OUT the remaining
        // amplitude is (1 - curve(t)), which should drop linearly in dB from 0dB
        // to -60dB. So amp(t) = 10^(-60 * t / 20); normalize amp into [floor..1]
        // then invert so the returned value is progress (0 → 1).
        // floor = 10^-3 (i.e. -60dB).
        val floor = 1e-3f
        val amp = 10f.pow(-60f * t / 20f)
        val normAmp = ((amp - floor) / (1f - floor)).coerceIn(0f, 1f)
        1f - normAmp
    }),
}

/**
 * Shared coroutine-driven master-volume ramp. Used by the sleep timer
 * (long fade-to-silence on expiry) and Pulsar song-endings (fade tail of
 * the final section). Last writer wins; callers coordinate at a higher
 * level if both might run concurrently.
 *
 * Thin wrapper over [SynthEngine.fadeMasterVolume]: arms the engine's
 * sample-accurate master fader, then suspends for [durationMs] so callers
 * can await completion. No polling loop — the engine interpolates per-sample.
 *
 * Cancellation: standard coroutine cancellation interrupts the suspending
 * `delay()`. The engine fader continues running independently to wherever
 * it had progressed; if the caller wants to abort the fade itself they must
 * issue a new `fadeMasterVolume` / `setMasterVolume` call.
 */
@SingleIn(AppScope::class)
@Inject
class MasterVolumeRamp(private val synthEngine: SynthEngine) {

    /**
     * Arm the engine's master fader to land on [target] after [durationMs]
     * milliseconds using [curve], then suspend for the same duration so
     * callers can await completion.
     *
     * @param stepMs Ignored (kept for source compatibility). The engine
     *   does sample-accurate interpolation; no polling needed.
     */
    suspend fun rampMasterVolumeTo(
        target: Float,
        durationMs: Long,
        @Suppress("UNUSED_PARAMETER") stepMs: Long = 100L,
        curve: FadeCurve = FadeCurve.LINEAR,
    ) {
        require(durationMs > 0) { "durationMs must be > 0" }
        synthEngine.fadeMasterVolume(target, durationMs.toInt(), curve)
        delay(durationMs)
    }
}

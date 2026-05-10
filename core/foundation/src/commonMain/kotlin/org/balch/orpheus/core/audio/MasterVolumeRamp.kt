package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay

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
 */
enum class FadeCurve(internal val curve: (Float) -> Float) {
    LINEAR({ it }),
    EASE_IN({ t -> t * t }),
    EASE_OUT({ t -> val u = 1f - t; 1f - u * u }),
}

/**
 * Shared coroutine-driven master-volume ramp. Used by the sleep timer
 * (long fade-to-silence on expiry) and Pulsar song-endings (fade tail of
 * the final section). Last writer wins; callers coordinate at a higher
 * level if both might run concurrently.
 *
 * Starts from the current master volume, lands on [target] after [durationMs]
 * real-time milliseconds, in [stepMs] steps. The [curve] shapes the in-between
 * values — see [FadeCurve].
 *
 * Cancellation: standard coroutine cancellation interrupts the loop on
 * the next `delay()`. The volume is left wherever the last write landed.
 */
@SingleIn(AppScope::class)
@Inject
class MasterVolumeRamp(private val synthEngine: SynthEngine) {

    suspend fun rampMasterVolumeTo(
        target: Float,
        durationMs: Long,
        stepMs: Long = 100L,
        curve: FadeCurve = FadeCurve.LINEAR,
    ) {
        require(durationMs > 0) { "durationMs must be > 0" }
        require(stepMs > 0) { "stepMs must be > 0" }
        val start = synthEngine.getMasterVolume()
        val steps = (durationMs / stepMs).coerceAtLeast(1L)
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val shaped = curve.curve(t)
            val v = start + (target - start) * shaped
            synthEngine.setMasterVolume(v)
            if (i < steps) delay(stepMs)
        }
    }
}

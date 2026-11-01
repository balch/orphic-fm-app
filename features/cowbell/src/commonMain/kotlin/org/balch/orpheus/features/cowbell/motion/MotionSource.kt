package org.balch.orpheus.features.cowbell.motion

import androidx.compose.runtime.Composable

/**
 * One frame's view of the phone, in display axes (x right, y down on screen, z out of it).
 * Reused every frame; the defaults are a phone lying flat and still.
 */
class MotionSample {
    /** Gravity in g, as an accelerometer reads it: flat and face up is (0, 0, +1). */
    var gravityX: Float = 0f
    var gravityY: Float = 0f
    var gravityZ: Float = 1f

    /** Linear acceleration in m/s² summed since the previous [MotionSource.read]. */
    var kickX: Float = 0f
    var kickY: Float = 0f

    fun rest() {
        gravityX = 0f
        gravityY = 0f
        gravityZ = 1f
        kickX = 0f
        kickY = 0f
    }
}

/**
 * The phone's motion sensors. Sensor callbacks arrive on a background thread; [read] and
 * [jolts] are for the UI thread. Implementations are no-ops while stopped or unavailable.
 */
interface MotionSource {
    /** False where there is nothing to read (desktop, web, or a device without the sensors). */
    val available: Boolean

    fun start()

    fun stop()

    /** Fills [into] with the latest gravity and the kick since the last read. Allocates nothing. */
    fun read(into: MotionSample)

    /** Linear-acceleration magnitudes (m/s²) stamped with the pointer clock, for strike-time lookup. */
    val jolts: JoltWindow
}

/** The platform's [MotionSource], started while the lifecycle is RESUMED and stopped on dispose. */
@Composable
expect fun rememberMotionSource(): MotionSource

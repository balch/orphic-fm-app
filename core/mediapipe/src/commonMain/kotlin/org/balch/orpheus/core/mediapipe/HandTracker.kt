package org.balch.orpheus.core.mediapipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific hand tracking provider.
 * Implementations handle camera capture and MediaPipe inference.
 */
interface HandTracker {
    /** Emits hand tracking results, or null when no hand is detected. */
    val results: Flow<HandTrackingResult?>

    /** Emits camera preview frames for UI rendering. */
    val cameraFrame: StateFlow<CameraFrame?>

    /** Whether camera hardware is available on this device. */
    val isAvailable: Boolean

    /** Start camera capture and hand tracking. */
    fun start()

    /** Stop camera capture and hand tracking. */
    fun stop()
}

package org.balch.orpheus.core.mediapipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Platform-specific hand tracking provider.
 * Implementations handle camera capture and MediaPipe inference.
 */
interface HandTracker {
    /** Emits hand tracking results, or null when no hand is detected. */
    val results: Flow<HandTrackingResult?>

    /** Emits camera preview frames for UI rendering. */
    val cameraFrame: StateFlow<CameraFrame?>

    /**
     * Whether camera hardware is available on this device.
     *
     * Must never block. This is read from UI-thread call paths, and an implementation
     * that cannot answer cheaply must report its best current guess rather than stall
     * to find out — then publish the settled answer through [availabilityChanges].
     */
    val isAvailable: Boolean

    /**
     * Emits whenever [isAvailable] settles or changes.
     *
     * Defaults to a flow that never emits, which is correct for every implementation
     * whose [isAvailable] is already a cheap and immediately-correct read: a package
     * manager feature query, a JS bridge presence check, a constant. Desktop is the
     * exception — it has to open a real camera device to find out — so it answers
     * `false` first and corrects itself here.
     */
    val availabilityChanges: Flow<Boolean> get() = emptyFlow()

    /** Start camera capture and hand tracking. */
    fun start()

    /** Stop camera capture and hand tracking. */
    fun stop()
}

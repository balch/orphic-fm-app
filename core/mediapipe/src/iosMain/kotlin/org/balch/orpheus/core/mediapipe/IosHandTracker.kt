package org.balch.orpheus.core.mediapipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS stub for [HandTracker].
 * Real implementation will use the Swift HandTrackingBridge via cinterop.
 */
class IosHandTracker : HandTracker {
    override val results: Flow<HandTrackingResult?> = emptyFlow()
    override val cameraFrame: StateFlow<CameraFrame?> = MutableStateFlow(null)
    override val isAvailable: Boolean = false
    override fun start() {}
    override fun stop() {}
}

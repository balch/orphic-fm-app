package org.balch.orpheus.core.mediapipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS [HandTracker]. Kotlin does not drive the camera here -- **Swift pushes into this**.
 *
 * The capture session, the MediaPipe `HandLandmarker`, and the pixel buffers all live in
 * `HandTrackingBridge.swift` in the iOS app, because MediaPipeTasksVision is a CocoaPod with no
 * Kotlin/Native bindings. Rather than cinterop out to it, the framework exports this class and
 * the app hands the graph's instance to the bridge at startup; the bridge then calls
 * [pushLandmarks] and [pushFrame] as frames arrive. That inverts the usual direction and is the
 * whole reason no cinterop layer exists.
 *
 * **This must be a singleton.** Its binding is `@SingleIn(AppScope::class)` in
 * the consuming app's iOS module and the graph exposes it. Two instances means Swift feeds one
 * while the UI observes the other, and the symptom is a camera that appears dead with no error
 * anywhere.
 *
 * [start] and [stop] do not touch hardware -- they publish [isRunning], which the Swift side
 * observes to know when to open and close the session.
 */
class IosHandTracker : HandTracker {

    private val _results = MutableSharedFlow<HandTrackingResult?>(extraBufferCapacity = 1)
    override val results: Flow<HandTrackingResult?> = _results.asSharedFlow()

    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    override val cameraFrame: StateFlow<CameraFrame?> = _cameraFrame.asStateFlow()

    private val _available = MutableStateFlow(false)
    override val isAvailable: Boolean get() = _available.value
    override val availabilityChanges: Flow<Boolean> = _available.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Whether the app currently wants frames. See [onRunningChanged]. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Set by Swift at startup to open and close the capture session.
     *
     * A callback rather than having Swift collect [isRunning]: consuming a Kotlin `StateFlow`
     * from Swift needs a coroutine bridge and a subscription to tear down, where the whole
     * requirement is "tell the other side when this flips". [isRunning] stays exposed for any
     * Kotlin observer.
     */
    var onRunningChanged: ((Boolean) -> Unit)? = null

    override fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        onRunningChanged?.invoke(true)
    }

    override fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        _cameraFrame.value = null
        _results.tryEmit(null)
        onRunningChanged?.invoke(false)
    }

    /**
     * Swift reports whether a usable capture device was found.
     *
     * [isAvailable] is documented as never blocking, so it answers from the last value published
     * here, defaulting to `false` until Swift knows. The honest default: a Simulator has no
     * `AVCaptureDevice` at all, and claiming a camera that does not exist would arm conducting
     * UI that can never respond.
     */
    fun setAvailable(available: Boolean) {
        _available.value = available
    }

    /**
     * One frame's landmarks, in the flat layout [parseHandLandmarkWire] documents.
     *
     * A flat `FloatArray` rather than nested arrays on purpose: this crosses the Swift/Kotlin
     * boundary 30 times a second, and bridging a nested `[[NSNumber]]` per frame allocates far
     * more than one contiguous buffer does.
     */
    fun pushLandmarks(data: FloatArray, timestampMs: Long) {
        _results.tryEmit(parseHandLandmarkWire(data, timestampMs))
    }

    /** BGRA_8888, matching [CameraFrame]. Swift mirrors at the capture connection, as Android does. */
    fun pushFrame(pixels: ByteArray, width: Int, height: Int) {
        _cameraFrame.value = CameraFrame(pixels = pixels, width = width, height = height)
    }
}

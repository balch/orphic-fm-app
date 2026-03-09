@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.mediapipe

import com.diamondedge.logging.logging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness

/**
 * WASM implementation of [HandTracker] using browser getUserMedia + MediaPipe Vision JS SDK.
 *
 * The JS bridge (mediapipe-hand-bridge.js) handles:
 * - Async loading of MediaPipe tasks-vision from CDN
 * - getUserMedia camera capture
 * - requestAnimationFrame detection loop
 * - Results stored as a flat Float32Array on globalThis
 *
 * This class polls the JS results via setInterval and emits to Kotlin flows.
 * It also polls error state from the bridge so failures surface to Kotlin logs.
 *
 * Camera frame preview is not available on WASM (pixel transfer is too expensive).
 * VIZ mode works — hand skeletons overlay the visualization background.
 */
class WasmHandTracker : HandTracker {

    private val log = logging("WasmHandTracker")

    private val _results = MutableSharedFlow<HandTrackingResult?>(extraBufferCapacity = 1)
    override val results: Flow<HandTrackingResult?> = _results.asSharedFlow()

    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    override val cameraFrame: StateFlow<CameraFrame?> = _cameraFrame.asStateFlow()

    override val isAvailable: Boolean
        get() = jsHasBridge() && jsHasGetUserMedia()

    private var pollIntervalId: Int = 0
    private var lastFrameSeq: Int = -1
    private var errorLogged: Boolean = false

    override fun start() {
        if (pollIntervalId != 0) return

        if (!jsHasBridge()) {
            log.error { "mediapipe-hand-bridge.js not loaded — __mpHandBridge missing from globalThis" }
            return
        }
        if (!jsHasGetUserMedia()) {
            log.error { "getUserMedia not available — requires HTTPS or localhost" }
            return
        }

        log.info { "Starting WASM hand tracking" }
        errorLogged = false

        // Async — starts camera and detection loop in JS
        jsStartCamera()

        // Poll for results at ~30fps, also checks error state
        pollIntervalId = jsSetHandTrackingInterval(33) {
            pollResults()
        }
    }

    override fun stop() {
        if (pollIntervalId == 0) return
        log.info { "Stopping WASM hand tracking" }

        jsClearHandTrackingInterval(pollIntervalId)
        pollIntervalId = 0
        lastFrameSeq = -1
        errorLogged = false

        jsStopCamera()
        _cameraFrame.value = null
    }

    private fun pollResults() {
        // Check for errors from the JS bridge (CDN load failure, camera denied, etc.)
        if (!errorLogged) {
            val error = jsGetBridgeError()
            if (error.isNotEmpty()) {
                log.error { "Hand tracking bridge error: $error" }
                errorLogged = true
                return
            }
        }

        val frameSeq = jsGetHandFrameSeq()
        if (frameSeq == lastFrameSeq) return // No new result
        lastFrameSeq = frameSeq

        val length = jsGetHandResultLength()
        if (length == 0) {
            _results.tryEmit(null)
            return
        }

        val numHands = jsGetHandResultValue(0).toInt()
        if (numHands == 0) {
            _results.tryEmit(null)
            return
        }

        val hands = (0 until numHands).map { h ->
            val base = 1 + h * 64
            val handednessValue = jsGetHandResultValue(base)
            // JS bridge encodes: 0 = Left, 1 = Right (user's perspective in mirror view)
            val handedness = if (handednessValue < 0.5f) Handedness.LEFT else Handedness.RIGHT

            val landmarks = (0 until 21).map { i ->
                val off = base + 1 + i * 3
                HandLandmark(
                    x = jsGetHandResultValue(off),
                    y = jsGetHandResultValue(off + 1),
                    z = jsGetHandResultValue(off + 2),
                )
            }
            TrackedHand(landmarks, handedness)
        }

        _results.tryEmit(HandTrackingResult(hands, frameSeq.toLong()))
    }
}

// --- JS bridge functions ---
// These access globalThis.__mpHandBridge set up by mediapipe-hand-bridge.js

private fun jsHasBridge(): Boolean =
    js("!!globalThis.__mpHandBridge")

private fun jsHasGetUserMedia(): Boolean =
    js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")

private fun jsStartCamera(): Unit =
    js("globalThis.__mpHandBridge && globalThis.__mpHandBridge.startCamera()")

private fun jsStopCamera(): Unit =
    js("globalThis.__mpHandBridge && globalThis.__mpHandBridge.stopCamera()")

private fun jsGetBridgeError(): String =
    js("(globalThis.__mpHandBridge && globalThis.__mpHandBridge.lastError) || ''")

private fun jsGetHandResultLength(): Int =
    js("(globalThis.__mpHandResult ? globalThis.__mpHandResult.length : 0)")

private fun jsGetHandResultValue(index: Int): Float =
    js("(globalThis.__mpHandResult ? globalThis.__mpHandResult[index] : 0)")

private fun jsGetHandFrameSeq(): Int =
    js("globalThis.__mpHandFrameSeq || 0")

private fun jsSetHandTrackingInterval(ms: Int, callback: () -> Unit): Int =
    js("setInterval(callback, ms)")

private fun jsClearHandTrackingInterval(id: Int): Unit =
    js("clearInterval(id)")

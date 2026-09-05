package org.balch.orpheus.core.mediapipe

import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness
import org.balch.orpheus.core.gestures.frameDebug
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Desktop implementation of [HandTracker] using JavaCV for camera capture
 * and MediaPipe C API (via JNI, LIVE_STREAM mode) for hand landmark detection.
 *
 * Camera capture and hand detection are decoupled:
 * - The capture loop grabs frames and publishes camera preview at full framerate.
 * - Each frame is sent to MediaPipe via [MediaPipeJni.detectAsync] (non-blocking).
 * - Detection results arrive asynchronously via a native callback.
 *
 * @param enableGestureRecognizer whether to run MediaPipe's GestureRecognizer graph, which adds
 *   [TrackedHand.gestureName] and [TrackedHand.gestureConfidence] on top of the landmarks. Callers
 *   that read only landmark geometry should pass `false`: the recognizer runs in VIDEO mode, so
 *   [MediaPipeJni.recognizeGesture] blocks the capture loop until inference finishes, where the
 *   landmarker's LIVE_STREAM path returns immediately and delivers results on a native thread. A
 *   graph error costs more still -- the loop backs off 100ms per failed frame, which drags the
 *   tracker to roughly 10fps and starves anything deriving velocity from frame deltas. Both paths
 *   are configured `num_hands = 2` and fill handedness identically, so turning this off changes
 *   nothing but the two gesture fields. Defaults to `true` to preserve existing behaviour.
 */
class DesktopHandTracker(
    private val deviceIndex: Int = 0,
    private val enableGestureRecognizer: Boolean = true,
) : HandTracker {

    private val log = logging("DesktopHandTracker")

    companion object {
        /** Camera capture format for FFmpeg — platform-dependent. */
        private val CAMERA_FORMAT: String? = run {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> "avfoundation"
                os.contains("linux") -> "v4l2"
                os.contains("win") -> "dshow"
                else -> null
            }
        }

        /**
         * The one capture mode both the probe and the capture loop request. Shared rather than
         * repeated: they must agree, because a probe that opens the device in a mode the
         * capture loop never uses proves nothing about whether capture will work.
         */
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480

        /** How long [stop] will wait for native cleanup before giving up on it. See [stop]. */
        private const val STOP_TIMEOUT_MS = 2_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private val _results = MutableSharedFlow<HandTrackingResult?>(extraBufferCapacity = 1)
    override val results: Flow<HandTrackingResult?> = _results.asSharedFlow()

    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    override val cameraFrame: StateFlow<CameraFrame?> = _cameraFrame.asStateFlow()

    private val _availability = MutableStateFlow(false)

    override val isAvailable: Boolean get() = _availability.value
    override val availabilityChanges: Flow<Boolean> = _availability.asStateFlow()

    /**
     * Opening a real camera device is the only way to learn whether one is usable, and
     * `avformat_open_input` can sit for a second or more. So it runs exactly once, here,
     * on an IO thread at construction — never on whatever thread happens to ask.
     *
     * This was a `by lazy`, which put the FFmpeg open on the first caller's thread and
     * made every other caller block on the lazy's own monitor. On desktop that first
     * caller is a `LaunchedEffect` body, i.e. the AWT event thread, so an app that probes
     * at startup stalled its UI on every launch. Worth knowing for whoever reads a thread
     * dump next: an AWT thread parked in `avformat_open_input` was this, and it looks
     * exactly like a dependency-injection deadlock without being one.
     */
    init {
        // Optimistic, and deliberately not a probe. Opening a device to ask "is there a
        // device" cost us an evening: FFmpeg's avfoundation backend needs a thread with a
        // running CFRunLoop to pump the capture session, so on a bare Dispatchers.IO worker
        // avformat_open_input never returns -- it SPINS, burning ~100% of a core for the life
        // of the process (jstack: RUNNABLE, cpu 50.5s of 51.3s elapsed). A coroutine timeout
        // cannot save you either: withTimeout cancels the coroutine, not the native frame, so
        // the thread keeps spinning invisibly.
        //
        // The capture loop opens the camera anyway and is the honest source of truth, so a
        // platform that has a camera API is reported available until capture proves otherwise.
        _availability.value = CAMERA_FORMAT != null
    }

    @Volatile
    private var nativePtr: Long = 0

    @Volatile
    private var useGestureRecognizer: Boolean = false

    // Stored after camera init so landmark coordinates can be remapped from
    // the padded-square space back to the original camera aspect ratio.
    @Volatile
    private var captureWidth: Int = 0
    @Volatile
    private var captureHeight: Int = 0

    /**
     * Callback from MediaPipe native thread (hand landmarker fallback).
     * Parses the float array and emits to [_results].
     */
    private val resultCallback = object : MediaPipeJni.ResultCallback {
        override fun onResult(result: FloatArray?, timestampMs: Long) {
            if (result != null) {
                _results.tryEmit(parseResult(result, timestampMs))
            } else {
                _results.tryEmit(null)
            }
        }
    }

    /**
     * Callback from MediaPipe native thread (gesture recognizer).
     * Parses the gesture float array and emits to [_results].
     */
    private val gestureResultCallback = object : MediaPipeJni.GestureResultCallback {
        override fun onResult(result: FloatArray?, gestureNames: Array<String?>?, timestampMs: Long) {
            if (result != null) {
                _results.tryEmit(parseGestureResult(result, gestureNames, timestampMs))
            } else {
                _results.tryEmit(null)
            }
        }
    }

    override fun start() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch {
            var grabber: FFmpegFrameGrabber? = null
            try {
                // Initialize JNI — try GestureRecognizer first, fall back
                // to HandLandmarker if it is unwanted or the model is unavailable.
                MediaPipeJni.initialize()

                val gestureModelPath = if (enableGestureRecognizer) {
                    try {
                        ModelExtractor.getGestureModelPath()
                    } catch (_: Exception) { null }
                } else {
                    null
                }

                if (gestureModelPath != null) {
                    useGestureRecognizer = true
                    nativePtr = MediaPipeJni.createGestureRecognizer(
                        gestureModelPath, gestureResultCallback,
                    )
                } else {
                    useGestureRecognizer = false
                    val modelPath = ModelExtractor.getModelPath()
                    nativePtr = MediaPipeJni.createLandmarker(modelPath, resultCallback)
                }

                grabber = FFmpegFrameGrabber("$deviceIndex").apply {
                    format = CAMERA_FORMAT
                    imageWidth = CAPTURE_WIDTH
                    imageHeight = CAPTURE_HEIGHT
                    frameRate = 30.0
                    // No FFmpeg `timeout` here, and that is a finding rather than an omission.
                    // An AVIOInterruptCB deadline was tried and MEASURED not to fire: the open
                    // sat 35s against a 5s deadline. When this call hangs it is not waiting on
                    // I/O -- it is blocked on a macOS camera-permission decision that never
                    // arrives, because the JVM's TCC identity comes from the top of its process
                    // ancestry and a gradle daemon rooted at a non-camera-granted app never
                    // prompts. Reproduces with the bare `ffmpeg` CLI from the same shell, so it
                    // is not a JVM or coroutine problem at all. Launch from a TCC-granted
                    // terminal, and prefix with `./gradlew --stop` so no poisoned daemon is
                    // reused.
                    start()
                }

                captureWidth = grabber.imageWidth
                captureHeight = grabber.imageHeight

                val converter = Java2DFrameConverter()
                var frameSequence = 0L
                var consecutiveErrors = 0

                while (isActive) {
                    val frame: Frame? = grabber.grab()
                    if (frame != null && frame.image != null) {
                        val rawImage = converter.convert(frame)
                        if (rawImage != null) {
                            // Mirror horizontally so the preview feels like a natural mirror
                            // and MediaPipe landmarks are in mirrored coordinates
                            val bufferedImage = mirrorHorizontal(rawImage)

                            // Publish camera frame for UI preview (non-blocking)
                            _cameraFrame.value = bufferedImageToCameraFrame(bufferedImage)

                            // Center-crop to square for MediaPipe (non-square causes abort
                            // in landmark_projection_calculator with NORM_RECT)
                            val squareImage = padToSquare(bufferedImage)

                            val rgbBytes = bufferedImageToRgb(squareImage)
                            if (useGestureRecognizer) {
                                val ok = MediaPipeJni.recognizeGesture(
                                    nativePtr, rgbBytes,
                                    squareImage.width, squareImage.height,
                                    frameSequence++,
                                )
                                if (!ok) {
                                    consecutiveErrors++
                                    if (consecutiveErrors == 1) {
                                        log.warn { "MediaPipe graph error, skipping frames to recover" }
                                    }
                                    // Back off to let the graph recover
                                    delay(100L)
                                    continue
                                }
                                consecutiveErrors = 0
                            } else {
                                MediaPipeJni.detectAsync(
                                    nativePtr, rgbBytes,
                                    squareImage.width, squareImage.height,
                                    frameSequence++,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.error(e) { "Capture error" }
            } finally {
                try {
                    grabber?.stop()
                    grabber?.release()
                } catch (_: Exception) { /* Ignore cleanup errors. */ }

                if (nativePtr != 0L) {
                    try {
                        if (useGestureRecognizer) {
                            MediaPipeJni.closeGestureRecognizer(nativePtr)
                        } else {
                            MediaPipeJni.closeLandmarker(nativePtr)
                        }
                    } catch (_: Exception) { /* Ignore cleanup errors. */ }
                    nativePtr = 0
                }
            }
        }
    }

    override fun stop() {
        val job = captureJob ?: return
        captureJob = null
        // Cancel the capture job and wait for native cleanup in the finally block.
        // This prevents SIGABRT from closing the native recognizer while a JNI call
        // is still in progress -- and it matters most for stop-then-start, which is what
        // toggling the camera panel does.
        job.cancel()
        // BOUNDED, and the bound is the whole point. This runs on the CALLER's thread, which
        // is a Compose click handler, i.e. the UI thread. Cancellation is cooperative, so a
        // coroutine parked in a native call never observes it -- an unbounded join then hangs
        // the UI thread forever with the camera device still claimed, and the machine needs a
        // reboot to get its camera back. GRABBER_TIMEOUT_MS should stop that happening at all;
        // this is the backstop for the next native call that finds a way to wedge. A leaked
        // capture thread is a far smaller failure than a frozen app.
        val stopped = runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { job.join() } != null }
        if (!stopped) {
            log.error {
                "Capture job ignored cancellation for ${STOP_TIMEOUT_MS}ms -- almost certainly " +
                    "stuck in a native call. Abandoning it rather than freezing the UI; the " +
                    "camera device may stay claimed until this process exits."
            }
        }
        _cameraFrame.value = null
    }

    /**
     * Parse the JNI result float array into a [HandTrackingResult].
     * Format: [numHands, hand0_handedness, hand0_x0,y0,z0,...x20,y20,z20, hand1_...].
     * Per hand: 1 handedness + 21*3 landmarks = 64 floats.
     */
    private fun parseResult(data: FloatArray, timestampMs: Long): HandTrackingResult {
        val numHands = data[0].toInt()
        val hands = (0 until numHands).map { h ->
            val base = 1 + h * 64
            // Invert handedness: the camera image is mirrored horizontally,
            // so MediaPipe's "Right" is actually the user's left hand.
            val handedness = if (data[base] >= 0.5f) Handedness.LEFT else Handedness.RIGHT
            val rawLandmarks = (0 until 21).map { i ->
                val off = base + 1 + i * 3
                HandLandmark(
                    x = data[off],
                    y = data[off + 1],
                    z = data[off + 2],
                )
            }
            TrackedHand(remapLandmarks(rawLandmarks), handedness)
        }
        return HandTrackingResult(
            hands = hands,
            frameSequence = timestampMs,
        )
    }

    /**
     * Parse the JNI gesture result float array into a [HandTrackingResult].
     * Format: [numHands, per-hand(handedness, gestureScore, 21*xyz)]
     * where per-hand = 65 floats. Gesture names come as a separate String[].
     */
    private fun parseGestureResult(
        data: FloatArray,
        names: Array<String?>?,
        timestampMs: Long,
    ): HandTrackingResult {
        val numHands = data[0].toInt()
        val hands = (0 until numHands).map { h ->
            val base = 1 + h * 65
            // Invert handedness (camera is mirrored)
            val handedness = if (data[base] < 0.5f) Handedness.RIGHT else Handedness.LEFT
            val gestureScore = data[base + 1]
            val rawLandmarks = (0 until 21).map { i ->
                val off = base + 2 + i * 3
                HandLandmark(data[off], data[off + 1], data[off + 2])
            }
            val gestureName = names?.getOrNull(h)
            if (gestureScore > 0.5f) {
                log.frameDebug { "GR frame: name=$gestureName score=${"%.2f".format(gestureScore)}" }
            }
            TrackedHand(remapLandmarks(rawLandmarks), handedness, gestureName, gestureScore)
        }
        return HandTrackingResult(
            hands = hands,
            frameSequence = timestampMs,
        )
    }

    /**
     * Remap landmarks from the padded-square coordinate space back to the
     * original camera aspect ratio. MediaPipe normalizes landmarks 0–1 against
     * the square; this converts them so 0–1 spans only the actual image region.
     */
    private fun remapLandmarks(landmarks: List<HandLandmark>): List<HandLandmark> {
        val w = captureWidth
        val h = captureHeight
        if (w <= 0 || h <= 0 || w == h) return landmarks

        val squareSize = maxOf(w, h)
        val padX = (squareSize - w) / 2f
        val padY = (squareSize - h) / 2f

        return landmarks.map { lm ->
            HandLandmark(
                x = (lm.x * squareSize - padX) / w,
                y = (lm.y * squareSize - padY) / h,
                z = lm.z,
            )
        }
    }

    /**
     * Convert BufferedImage to RGB byte array (3 bytes per pixel) for MediaPipe.
     * MediaPipe expects kMpImageFormatSrgb = R, G, B byte order.
     */
    private fun bufferedImageToRgb(image: BufferedImage): ByteArray {
        val argbImage = ensureArgb(image)
        val intPixels = (argbImage.raster.dataBuffer as DataBufferInt).data
        val bytes = ByteArray(argbImage.width * argbImage.height * 3)

        for (i in intPixels.indices) {
            val pixel = intPixels[i]
            val offset = i * 3
            bytes[offset] = (pixel ushr 16).toByte()     // R
            bytes[offset + 1] = (pixel ushr 8).toByte()  // G
            bytes[offset + 2] = pixel.toByte()            // B
        }

        return bytes
    }

    /**
     * Convert BufferedImage to BGRA CameraFrame for Skia UI rendering.
     */
    private fun bufferedImageToCameraFrame(image: BufferedImage): CameraFrame {
        val argbImage = ensureArgb(image)
        val intPixels = (argbImage.raster.dataBuffer as DataBufferInt).data
        val bytes = ByteArray(argbImage.width * argbImage.height * 4)

        for (i in intPixels.indices) {
            val pixel = intPixels[i]
            val offset = i * 4
            bytes[offset] = pixel.toByte()                // B
            bytes[offset + 1] = (pixel ushr 8).toByte()   // G
            bytes[offset + 2] = (pixel ushr 16).toByte()  // R
            bytes[offset + 3] = (pixel ushr 24).toByte()  // A
        }

        return CameraFrame(pixels = bytes, width = argbImage.width, height = argbImage.height)
    }

    private fun ensureArgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_ARGB) return image
        return BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { dest ->
            val g = dest.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
    }

    /** Pad to square (letterbox) so MediaPipe landmark projection doesn't abort.
     *  Preserves full field of view — no hand data lost. */
    private fun padToSquare(image: BufferedImage): BufferedImage {
        if (image.width == image.height) return image
        val size = maxOf(image.width, image.height)
        val padded = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = padded.createGraphics()
        // Black background (ARGB default is transparent black, fill opaque)
        g.color = java.awt.Color.BLACK
        g.fillRect(0, 0, size, size)
        // Center the original image
        g.drawImage(image, (size - image.width) / 2, (size - image.height) / 2, null)
        g.dispose()
        return padded
    }

    /** Flip image horizontally so the camera acts like a mirror. */
    private fun mirrorHorizontal(image: BufferedImage): BufferedImage {
        val mirrored = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = mirrored.createGraphics()
        g.drawImage(image, AffineTransform(-1.0, 0.0, 0.0, 1.0, image.width.toDouble(), 0.0), null)
        g.dispose()
        return mirrored
    }
}

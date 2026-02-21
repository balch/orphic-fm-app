package org.balch.orpheus.core.mediapipe

import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness
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
 */
class DesktopHandTracker(
    private val deviceIndex: Int = 0,
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
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private val _results = MutableSharedFlow<HandTrackingResult?>(extraBufferCapacity = 1)
    override val results: Flow<HandTrackingResult?> = _results.asSharedFlow()

    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    override val cameraFrame: StateFlow<CameraFrame?> = _cameraFrame.asStateFlow()

    override val isAvailable: Boolean by lazy {
        if (CAMERA_FORMAT == null) return@lazy false
        try {
            val grabber = FFmpegFrameGrabber("$deviceIndex").apply {
                format = CAMERA_FORMAT
                frameRate = 30.0
            }
            grabber.start()
            grabber.stop()
            grabber.release()
            true
        } catch (e: Exception) {
            System.err.println("[Orpheus] Camera availability check failed: ${e.message}")
            false
        }
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
                // to HandLandmarker if model is unavailable.
                MediaPipeJni.initialize()

                val gestureModelPath = try {
                    ModelExtractor.getGestureModelPath()
                } catch (_: Exception) { null }

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
                    imageWidth = 640
                    imageHeight = 480
                    frameRate = 30.0
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
                System.err.println("[Orpheus] DesktopHandTracker capture error: ${e.message}")
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
        // is still in progress.
        job.cancel()
        runBlocking { job.join() }
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
                log.debug { "GR frame: name=$gestureName score=${"%.2f".format(gestureScore)}" }
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

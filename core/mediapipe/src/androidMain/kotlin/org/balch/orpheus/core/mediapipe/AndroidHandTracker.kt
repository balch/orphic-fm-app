package org.balch.orpheus.core.mediapipe

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Android implementation of [HandTracker] using MediaPipe GestureRecognizer in LIVE_STREAM mode,
 * with a fallback to HandLandmarker if the gesture model is unavailable.
 *
 * This tracker self-manages the front camera via CameraX — no external camera setup needed.
 * Call [start] to begin tracking and [stop] to release the camera.
 *
 * @param context Android application context.
 * @param gestureModelAssetPath path to the gesture_recognizer.task model in assets.
 * @param landmarkerModelAssetPath fallback path to the hand_landmarker.task model in assets.
 */
class AndroidHandTracker(
    private val context: Context,
    private val gestureModelAssetPath: String = "models/gesture_recognizer.task",
    private val landmarkerModelAssetPath: String = "models/hand_landmarker.task",
) : HandTracker {

    private val _results = MutableSharedFlow<HandTrackingResult?>(extraBufferCapacity = 1)
    override val results: Flow<HandTrackingResult?> = _results.asSharedFlow()

    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    override val cameraFrame: StateFlow<CameraFrame?> = _cameraFrame.asStateFlow()

    override val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

    private var gestureRecognizer: GestureRecognizer? = null
    private var handLandmarker: HandLandmarker? = null
    private var useGestureRecognizer: Boolean = false
    private var lifecycleOwner: TrackerLifecycleOwner? = null
    private var analysisExecutor = Executors.newSingleThreadExecutor()

    // Pre-allocated buffers to reduce GC pressure at 30fps
    private var reusableMirrorBitmap: Bitmap? = null
    private var reusableFrameBuffer: ByteBuffer? = null

    override fun start() {
        if (gestureRecognizer != null || handLandmarker != null) return

        // Recreate executor if it was shut down by a previous stop() call
        if (analysisExecutor.isShutdown) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }

        // Try GestureRecognizer first — it provides landmarks + gesture classification in one pass.
        // Fall back to HandLandmarker if the gesture model is missing.
        val started = try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(gestureModelAssetPath)
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(::onGestureResult)
                .setErrorListener { _ ->
                    _results.tryEmit(null)
                }
                .build()

            gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
            useGestureRecognizer = true
            android.util.Log.i("AndroidHandTracker", "Using GestureRecognizer")
            true
        } catch (e: Throwable) {
            android.util.Log.w("AndroidHandTracker", "GestureRecognizer unavailable, falling back to HandLandmarker", e)
            false
        }

        if (!started) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(landmarkerModelAssetPath)
                    .build()

                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener(::onLandmarkerResult)
                    .setErrorListener { _ ->
                        _results.tryEmit(null)
                    }
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
                useGestureRecognizer = false
                android.util.Log.i("AndroidHandTracker", "Using HandLandmarker (fallback)")
            } catch (e: Throwable) {
                android.util.Log.e("AndroidHandTracker", "Failed to create HandLandmarker", e)
                _results.tryEmit(null)
                return
            }
        }

        // Set up CameraX with a synthetic LifecycleOwner
        val owner = TrackerLifecycleOwner()
        lifecycleOwner = owner
        owner.start()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            // Guard against rapid toggle — if stop() was called before provider resolved
            val currentOwner = lifecycleOwner ?: return@addListener

            val cameraProvider = cameraProviderFuture.get()

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                // Guard against stop() called while analysis is in flight
                if (lifecycleOwner == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(currentOwner, cameraSelector, imageAnalysis)
        }, { command -> Handler(Looper.getMainLooper()).post(command) })
    }

    override fun stop() {
        lifecycleOwner?.stop()
        lifecycleOwner = null
        gestureRecognizer?.close()
        gestureRecognizer = null
        handLandmarker?.close()
        handLandmarker = null
        useGestureRecognizer = false
        analysisExecutor.shutdown()
        reusableMirrorBitmap?.recycle()
        reusableMirrorBitmap = null
        reusableFrameBuffer = null
    }

    /**
     * Convert an ImageProxy (RGBA_8888 from CameraX) to a Bitmap, apply sensor rotation,
     * and feed to processFrame.
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000 // ns → ms
        val plane = imageProxy.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (rowStride == width * pixelStride) {
            // No padding — direct copy
            buffer.rewind()
            rawBitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Row stride has padding — copy row by row
            val rowBuffer = ByteArray(rowStride)
            val pixels = IntArray(width)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowBuffer, 0, rowStride)
                for (x in 0 until width) {
                    val i = x * pixelStride
                    val r = rowBuffer[i].toInt() and 0xFF
                    val g = rowBuffer[i + 1].toInt() and 0xFF
                    val b = rowBuffer[i + 2].toInt() and 0xFF
                    val a = rowBuffer[i + 3].toInt() and 0xFF
                    pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                rawBitmap.setPixels(pixels, 0, width, 0, y, width, 1)
            }
        }
        imageProxy.close()

        // Apply sensor rotation so the frame is upright before processing.
        // CameraX reports how many degrees the image must be rotated clockwise
        // to match the target (display) orientation.
        val bitmap = if (rotationDegrees != 0) {
            val rotateMatrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat(), width / 2f, height / 2f)
            }
            Bitmap.createBitmap(rawBitmap, 0, 0, width, height, rotateMatrix, true).also {
                if (it !== rawBitmap) rawBitmap.recycle()
            }
        } else {
            rawBitmap
        }

        processFrame(bitmap, timestampMs)
    }

    /**
     * Feed a camera frame to the hand landmarker for async detection.
     *
     * @param bitmap ARGB_8888 bitmap from the camera.
     * @param timestampMs frame timestamp in milliseconds (must be monotonically increasing).
     */
    internal fun processFrame(bitmap: Bitmap, timestampMs: Long) {
        // Mirror horizontally so the preview feels like a natural mirror
        // and MediaPipe landmarks are in mirrored coordinates
        val mirrored = mirrorHorizontal(bitmap)

        publishCameraFrame(mirrored)

        val mpImage = BitmapImageBuilder(mirrored).build()
        if (useGestureRecognizer) {
            gestureRecognizer?.recognizeAsync(mpImage, timestampMs) ?: return
        } else {
            handLandmarker?.detectAsync(mpImage, timestampMs) ?: return
        }
    }

    private val mirrorMatrix = Matrix().apply { preScale(-1f, 1f) }

    private fun mirrorHorizontal(bitmap: Bitmap): Bitmap {
        // Reuse the destination bitmap if dimensions match
        val existing = reusableMirrorBitmap
        val dest = if (existing != null && !existing.isRecycled &&
            existing.width == bitmap.width && existing.height == bitmap.height
        ) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                reusableMirrorBitmap = it
            }
        }

        val canvas = android.graphics.Canvas(dest)
        mirrorMatrix.reset()
        mirrorMatrix.preScale(-1f, 1f, bitmap.width / 2f, 0f)
        canvas.drawBitmap(bitmap, mirrorMatrix, null)
        return dest
    }

    /**
     * Callback for GestureRecognizer results — provides landmarks, handedness, and gesture classification.
     */
    private fun onGestureResult(result: GestureRecognizerResult, inputImage: com.google.mediapipe.framework.image.MPImage) {
        if (result.landmarks().isEmpty()) {
            _results.tryEmit(null)
            return
        }

        val hands = result.landmarks().mapIndexed { index, mpLandmarks ->
            val landmarks = mpLandmarks.map { lm ->
                HandLandmark(x = lm.x(), y = lm.y(), z = lm.z())
            }
            val handedness = resolveHandedness(result.handedness(), index)
            val gestureName = result.gestures().getOrNull(index)
                ?.firstOrNull()?.categoryName()
            val gestureConfidence = result.gestures().getOrNull(index)
                ?.firstOrNull()?.score() ?: 0f
            TrackedHand(landmarks, handedness, gestureName, gestureConfidence)
        }

        _results.tryEmit(
            HandTrackingResult(
                hands = hands,
                frameSequence = result.timestampMs(),
            )
        )
    }

    /**
     * Callback for HandLandmarker results (fallback) — provides landmarks and handedness only.
     */
    private fun onLandmarkerResult(result: HandLandmarkerResult, inputImage: com.google.mediapipe.framework.image.MPImage) {
        if (result.landmarks().isEmpty()) {
            _results.tryEmit(null)
            return
        }

        val hands = result.landmarks().mapIndexed { index, mpLandmarks ->
            val landmarks = mpLandmarks.map { lm ->
                HandLandmark(x = lm.x(), y = lm.y(), z = lm.z())
            }
            val handedness = resolveHandedness(result.handedness(), index)
            TrackedHand(landmarks, handedness)
        }

        _results.tryEmit(
            HandTrackingResult(
                hands = hands,
                frameSequence = result.timestampMs(),
            )
        )
    }

    /**
     * Resolve handedness from MediaPipe result, inverting because the camera image is mirrored.
     * MediaPipe reports handedness from the subject's perspective on the original image;
     * after horizontal mirroring, left and right are swapped.
     */
    private fun resolveHandedness(
        handednessList: List<List<com.google.mediapipe.tasks.components.containers.Category>>,
        index: Int,
    ): Handedness {
        return if (index < handednessList.size && handednessList[index].isNotEmpty()) {
            val label = handednessList[index][0].categoryName()
            // Invert: mirrored camera swaps handedness
            if (label.equals("Left", ignoreCase = true)) Handedness.RIGHT else Handedness.LEFT
        } else {
            Handedness.RIGHT
        }
    }

    private fun publishCameraFrame(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val requiredSize = width * height * 4
        val buffer = reusableFrameBuffer.let { existing ->
            if (existing != null && existing.capacity() >= requiredSize) {
                existing.clear()
                existing
            } else {
                ByteBuffer.allocate(requiredSize).also { reusableFrameBuffer = it }
            }
        }
        bitmap.copyPixelsToBuffer(buffer)
        // CameraFrame needs its own copy since the buffer is reused
        val pixels = ByteArray(requiredSize)
        buffer.rewind()
        buffer.get(pixels)
        _cameraFrame.value = CameraFrame(
            pixels = pixels,
            width = width,
            height = height,
        )
    }

    /**
     * Synthetic LifecycleOwner for CameraX binding without an Activity/Fragment.
     * Lifecycle transitions are posted to the main thread as required by Android.
     */
    private class TrackerLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun getLifecycle(): Lifecycle = registry

        fun start() {
            mainHandler.post {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        fun stop() {
            mainHandler.post {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }
}

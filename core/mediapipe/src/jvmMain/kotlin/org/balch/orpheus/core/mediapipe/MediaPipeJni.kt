package org.balch.orpheus.core.mediapipe

import java.io.File
import java.nio.file.Files
import java.util.logging.Logger

/**
 * JNI bindings for the MediaPipe Hand Landmarker and Gesture Recognizer C APIs.
 *
 * Loads a single combined native library (platform-specific: `.dylib`/`.so`/`.dll`) that
 * statically links MediaPipe, OpenCV, protobuf, and our JNI shim — no external
 * Homebrew dependencies required at runtime.
 *
 * HandLandmarker uses LIVE_STREAM mode (async results via [ResultCallback]).
 * GestureRecognizer uses VIDEO mode (synchronous) to avoid a crash in MediaPipe's
 * async packet lifecycle for Eigen::Matrix holders. Gesture names are passed as
 * strings directly per-frame (no index-based lookup table).
 */
object MediaPipeJni {

    /**
     * Callback interface for LIVE_STREAM async results.
     * Called on a MediaPipe native thread — implementations must be thread-safe.
     *
     * @param result float array [handedness, x0,y0,z0, ..., x20,y20,z20] (64 floats),
     *               or null if no hand detected.
     * @param timestampMs the timestamp of the frame that produced this result.
     */
    interface ResultCallback {
        fun onResult(result: FloatArray?, timestampMs: Long)
    }

    /**
     * Callback interface for gesture recognition results.
     * Called on the caller's thread (VIDEO mode is synchronous).
     *
     * @param result float array of landmarks + scores, or null if no hand detected.
     * @param gestureNames gesture name strings per hand (one per detected hand), or null.
     * @param timestampMs frame timestamp.
     */
    interface GestureResultCallback {
        fun onResult(result: FloatArray?, gestureNames: Array<String?>?, timestampMs: Long)
    }

    private val logger = Logger.getLogger(MediaPipeJni::class.java.name)
    private var initialized = false

    /**
     * Extract the native dylib from resources and load it.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * A single combined dylib provides both HandLandmarker and GestureRecognizer.
     */
    @Synchronized
    fun initialize() {
        if (initialized) return

        val arch = System.getProperty("os.arch").let { arch ->
            when {
                arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
                arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
                else -> arch
            }
        }
        val os = System.getProperty("os.name").lowercase().let { os ->
            when {
                os.contains("mac") -> "darwin"
                os.contains("linux") -> "linux"
                os.contains("win") -> "windows"
                else -> os
            }
        }
        val platform = "$os-$arch"
        val tempDir = Files.createTempDirectory("mediapipe-native").toFile()
        tempDir.deleteOnExit()

        val lib = libName("mediapipe_jni")
        extractNativeLib(platform, lib, tempDir)

        // macOS on Apple Silicon requires code-signed binaries.
        // Ad-hoc sign the extracted dylib before loading.
        if (os == "darwin") {
            val libFile = tempDir.resolve(lib)
            ProcessBuilder("codesign", "-s", "-", libFile.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }

        System.load(tempDir.resolve(lib).absolutePath)

        initialized = true
    }

    private fun libName(baseName: String): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> "lib$baseName.dylib"
            os.contains("linux") -> "lib$baseName.so"
            os.contains("win") -> "$baseName.dll"
            else -> "lib$baseName.dylib"
        }
    }

    private fun extractNativeLib(platform: String, libFileName: String, tempDir: File) {
        val resourcePath = "/native/$platform/$libFileName"
        val stream = MediaPipeJni::class.java.getResourceAsStream(resourcePath)
            ?: error("Native library not found in resources: $resourcePath")
        val outFile = tempDir.resolve(libFileName)
        stream.use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
        outFile.deleteOnExit()
    }

    /**
     * Create a HandLandmarker in LIVE_STREAM mode.
     * Results arrive asynchronously via [callback].
     *
     * @param modelPath absolute path to the hand_landmarker.task model file.
     * @param callback receives detection results on a native thread.
     * @return native pointer (opaque handle).
     */
    fun createLandmarker(modelPath: String, callback: ResultCallback): Long {
        return nativeCreateLandmarker(modelPath, callback)
    }

    /**
     * Send a frame for async hand detection. Returns immediately.
     * Results will arrive later via the [ResultCallback] passed to [createLandmarker].
     *
     * @param landmarkerPtr native pointer from [createLandmarker].
     * @param rgbPixels RGB byte array (3 bytes per pixel, width*height*3 total).
     * @param width frame width in pixels.
     * @param height frame height in pixels.
     * @param timestampMs monotonically increasing timestamp.
     */
    fun detectAsync(
        landmarkerPtr: Long,
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ) {
        nativeDetectAsync(landmarkerPtr, rgbPixels, width, height, timestampMs)
    }

    /**
     * Close the HandLandmarker and release native resources.
     */
    fun closeLandmarker(landmarkerPtr: Long) {
        nativeCloseLandmarker(landmarkerPtr)
    }

    /**
     * Create a GestureRecognizer in VIDEO mode (synchronous).
     *
     * @param modelPath absolute path to the gesture_recognizer.task model file.
     * @param callback receives recognition results on the calling thread.
     * @return native pointer (opaque handle).
     */
    fun createGestureRecognizer(modelPath: String, callback: GestureResultCallback): Long {
        return nativeCreateGestureRecognizer(modelPath, 2, callback)
    }

    /**
     * Process a video frame for gesture recognition. Blocks until recognition completes,
     * then calls the [GestureResultCallback] on the calling thread before returning.
     *
     * @param recognizerPtr native pointer from [createGestureRecognizer].
     * @param rgbPixels RGB byte array (3 bytes per pixel, width*height*3 total).
     * @param width frame width in pixels.
     * @param height frame height in pixels.
     * @param timestampMs monotonically increasing timestamp.
     */
    fun recognizeGesture(
        recognizerPtr: Long,
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ): Boolean {
        return nativeRecognizeGestureForVideo(recognizerPtr, rgbPixels, width, height, timestampMs)
    }

    /**
     * Close the GestureRecognizer and release native resources.
     */
    fun closeGestureRecognizer(recognizerPtr: Long) {
        nativeCloseGestureRecognizer(recognizerPtr)
    }

    // --- JNI native declarations ---

    private external fun nativeCreateLandmarker(modelPath: String, callback: ResultCallback): Long
    private external fun nativeDetectAsync(
        landmarkerPtr: Long,
        pixelData: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    )
    private external fun nativeCloseLandmarker(landmarkerPtr: Long)

    private external fun nativeCreateGestureRecognizer(
        modelPath: String,
        numHands: Int,
        callback: GestureResultCallback,
    ): Long

    private external fun nativeRecognizeGestureForVideo(
        recognizerPtr: Long,
        pixelData: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ): Boolean

    private external fun nativeCloseGestureRecognizer(recognizerPtr: Long)
}

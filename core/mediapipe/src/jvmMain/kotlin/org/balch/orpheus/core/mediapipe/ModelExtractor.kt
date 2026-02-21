package org.balch.orpheus.core.mediapipe

import java.nio.file.Files

/**
 * Extracts the bundled hand_landmarker.task model from classpath resources
 * to a temp file, so the MediaPipe C API can load it by path.
 */
internal object ModelExtractor {

    private var extractedPath: String? = null

    @Synchronized
    fun getModelPath(): String {
        extractedPath?.let { return it }

        val resourcePath = "/models/hand_landmarker.task"
        val stream = ModelExtractor::class.java.getResourceAsStream(resourcePath)
            ?: error("Model not found in resources: $resourcePath")

        val tempFile = Files.createTempFile("hand_landmarker", ".task").toFile()
        tempFile.deleteOnExit()
        stream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }

        extractedPath = tempFile.absolutePath
        return tempFile.absolutePath
    }

    private var gestureModelPath: String? = null

    @Synchronized
    fun getGestureModelPath(): String {
        gestureModelPath?.let { return it }

        val resourcePath = "/models/gesture_recognizer.task"
        val stream = ModelExtractor::class.java.getResourceAsStream(resourcePath)
            ?: error("Gesture recognizer model not found: $resourcePath")

        val tempFile = Files.createTempFile("gesture_recognizer", ".task").toFile()
        tempFile.deleteOnExit()
        stream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }

        gestureModelPath = tempFile.absolutePath
        return tempFile.absolutePath
    }
}

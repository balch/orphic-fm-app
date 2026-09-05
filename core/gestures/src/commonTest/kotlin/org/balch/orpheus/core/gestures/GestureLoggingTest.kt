package org.balch.orpheus.core.gestures

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ASL and fusion diagnostics sit on the camera frame path. Recognition runs many times a
 * second, so anything logged there floods every capture taken while a hand is visible.
 */
class GestureLoggingTest {

    /** Collects whatever reaches KmLogging, at any level. */
    private class Capture : Logger {
        val messages = mutableListOf<String>()

        override fun verbose(tag: String, msg: String) { messages += msg }
        override fun debug(tag: String, msg: String) { messages += msg }
        override fun info(tag: String, msg: String) { messages += msg }
        override fun warn(tag: String, msg: String, t: Throwable?) { messages += msg }
        override fun error(tag: String, msg: String, t: Throwable?) { messages += msg }

        override fun isLoggingVerbose() = true
        override fun isLoggingDebug() = true
        override fun isLoggingInfo() = true
        override fun isLoggingWarning() = true
        override fun isLoggingError() = true
    }

    private val capture = Capture()
    private val interpreter = GestureInterpreter()

    /**
     * Fist with the thumb riding above the knuckles: classifies as THUMBS_UP, so both the
     * ASL sign log and the FUSE decision log have something to say.
     */
    private fun thumbsUp(): List<HandLandmark> {
        val base = List(21) { HandLandmark(0.5f, 0.5f, 0f) }
        return base.toMutableList().apply {
            this[LandmarkIndex.WRIST] = HandLandmark(0.5f, 0.9f, 0f)
            this[LandmarkIndex.MIDDLE_MCP] = HandLandmark(0.5f, 0.6f, 0f)
            this[LandmarkIndex.INDEX_MCP] = HandLandmark(0.45f, 0.6f, 0f)
            // Every finger curled: tip below its PIP joint.
            this[LandmarkIndex.INDEX_PIP] = HandLandmark(0.45f, 0.55f, 0f)
            this[LandmarkIndex.INDEX_TIP] = HandLandmark(0.45f, 0.65f, 0f)
            this[LandmarkIndex.MIDDLE_PIP] = HandLandmark(0.50f, 0.55f, 0f)
            this[LandmarkIndex.MIDDLE_TIP] = HandLandmark(0.50f, 0.65f, 0f)
            this[LandmarkIndex.RING_PIP] = HandLandmark(0.55f, 0.55f, 0f)
            this[LandmarkIndex.RING_TIP] = HandLandmark(0.55f, 0.65f, 0f)
            this[LandmarkIndex.PINKY_PIP] = HandLandmark(0.60f, 0.55f, 0f)
            this[LandmarkIndex.PINKY_TIP] = HandLandmark(0.60f, 0.65f, 0f)
            // Thumb well above the index knuckle.
            this[LandmarkIndex.THUMB_TIP] = HandLandmark(0.35f, 0.20f, 0f)
        }
    }

    @AfterTest
    fun tearDown() {
        KmLogging.removeLogger(capture)
        GestureLogging.frameLogging = false
    }

    @Test
    fun `frame path logs nothing by default`() {
        KmLogging.addLogger(capture)

        val state = interpreter.interpret(thumbsUp(), Handedness.RIGHT)

        assertEquals(AslSign.THUMBS_UP, state.aslSign, "fixture should classify, or this proves nothing")
        assertEquals(emptyList(), capture.messages, "per-frame gesture path must stay silent by default")
    }

    @Test
    fun `frame path logs the diagnostic when enabled`() {
        KmLogging.addLogger(capture)
        GestureLogging.frameLogging = true

        interpreter.interpret(thumbsUp(), Handedness.RIGHT)

        assertTrue(
            capture.messages.any { it.startsWith("ASL:") },
            "expected an ASL diagnostic, got ${capture.messages}",
        )
        assertTrue(
            capture.messages.any { it.startsWith("FUSE") },
            "expected a FUSE diagnostic, got ${capture.messages}",
        )
    }
}

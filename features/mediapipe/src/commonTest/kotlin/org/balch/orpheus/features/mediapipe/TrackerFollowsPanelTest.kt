package org.balch.orpheus.features.mediapipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import org.balch.orpheus.core.mediapipe.CameraFrame
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.mediapipe.HandTrackingResult

private class RecordingHandTracker(override val isAvailable: Boolean = true) : HandTracker {
    val calls = mutableListOf<String>()
    override val results: Flow<HandTrackingResult?> = emptyFlow()
    override val cameraFrame: StateFlow<CameraFrame?> = MutableStateFlow(null)
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
}

class TrackerFollowsPanelTest {

    @Test
    fun `a rebuild pauses an enabled tracker and the rebuilt panel resumes it`() {
        val tracker = RecordingHandTracker()

        tracker.followPanel(attached = false, enabled = true)
        tracker.followPanel(attached = true, enabled = true)

        assertEquals(listOf("stop", "start"), tracker.calls)
    }

    /** A panel coming on screen must never open the camera on its own. */
    @Test
    fun `a panel appearing does not start a tracker the user turned off`() {
        val tracker = RecordingHandTracker()

        tracker.followPanel(attached = true, enabled = false)
        tracker.followPanel(attached = false, enabled = false)

        assertEquals(emptyList(), tracker.calls)
    }

    @Test
    fun `a device with no camera is left alone`() {
        val tracker = RecordingHandTracker(isAvailable = false)

        tracker.followPanel(attached = true, enabled = true)

        assertEquals(emptyList(), tracker.calls)
    }
}

package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.features.cowbell.motion.MotionSample
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The VM's scope is never cancelled in production, so the panel leaving composition is what lets
 * go of a held SYNTH note.
 */
class CowbellPanelDisposeTest {

    @Test
    fun removingThePanelMidHoldReleasesAll() {
        val recording = RecordingActions()
        val feature = recording.feature(CowbellUiState(mode = CowbellMode.SYNTH))
        var shown by mutableStateOf(true)
        CowbellScene(320, 330) {
            if (shown) CowbellPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
        }.use { s ->
            s.press(s.bell().center)
            assertEquals(listOf("hold"), recording.calls)

            shown = false
            s.frame()

            assertTrue("releaseAll" in recording.calls, "calls: ${recording.calls}")
        }
    }

    @Test
    fun motionStopsOnDispose() {
        val recording = RecordingActions()
        val feature = recording.feature(CowbellUiState())
        val motion = FakeMotion()
        var shown by mutableStateOf(true)
        CowbellScene(320, 330) {
            CompositionLocalProvider(LocalCowbellMotion provides motion) {
                if (shown) CowbellPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
            }
        }.use { s ->
            assertEquals(1, motion.starts, "composing the panel starts the sensors")
            assertTrue(motion.running)

            shown = false
            s.frame()

            assertEquals(1, motion.stops, "removing the panel stops the sensors")
            assertTrue(!motion.running)
            assertTrue("releaseAll" in recording.calls, "calls: ${recording.calls}")
        }
    }

    @Test
    fun removingTheBellZeroesTheTiltAndClearsMotion() {
        val recording = RecordingActions()
        val feature = recording.feature(CowbellUiState(motion = true))
        val phone = object : MotionSource {
            @Volatile var gy = 0f
            override val available = true
            override val jolts = JoltWindow(capacity = 1)
            override fun start() {}
            override fun stop() {}
            override fun read(into: MotionSample) {
                into.rest()
                into.gravityY = gy
            }
        }
        var shown by mutableStateOf(true)
        CowbellScene(320, 330) {
            CompositionLocalProvider(LocalCowbellMotion provides phone) {
                if (shown) CowbellPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
            }
        }.use { s ->
            // Settle on the holding angle, then tip the top edge toward the player.
            s.frames(1000)
            phone.gy = 0.5f
            s.frames(500)
            assertTrue(recording.tilts.last() != 0f, "tilts ${recording.tilts.takeLast(3)}")
            assertEquals(true, recording.motionAvailable.last())

            shown = false
            s.frame()

            assertEquals(0f, recording.tilts.last(), "the tilt is let go on dispose")
            assertEquals(false, recording.motionAvailable.last())
        }
    }
}

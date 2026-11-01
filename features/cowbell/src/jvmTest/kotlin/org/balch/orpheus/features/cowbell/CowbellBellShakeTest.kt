package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.features.cowbell.motion.MotionSample
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bell's frame loop end to end: tilt, the fill's strikes, and nothing recomposing.
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellBellShakeTest*'
 */
class CowbellBellShakeTest {

    private companion object {
        const val SHAKE_PRESS_SEED = 7
    }

    /** A phone that reads whatever gravity it is given. */
    private class TiltablePhone : MotionSource {
        @Volatile var gx = 0f
        @Volatile var gy = 0f
        override val available = true
        override val jolts = JoltWindow(capacity = 1)
        override fun start() {}
        override fun stop() {}
        override fun read(into: MotionSample) {
            into.rest()
            into.gravityX = gx
            into.gravityY = gy
        }
    }

    private fun bellScene(
        recording: RecordingActions,
        mode: CowbellMode,
        motion: MotionSource? = null,
        fill: CowbellFill = CowbellFill.MARBLE,
        random: Random = Random.Default,
    ) = CowbellScene(200, 300) {
        CompositionLocalProvider(LocalCowbellMotion provides motion) {
            CowbellBell3D(
                mode = mode,
                actions = recording.actions,
                bend = { 0f },
                modifier = Modifier.fillMaxSize(),
                fill = fill,
                motionOn = true,
                random = random,
            )
        }
    }

    @Test
    fun desktopShakeHoverTiltsTheFillIntoStrikes() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.SHAKE).use { s ->
            val bell = s.bell()
            s.move(Offset(bell.right - 1f, bell.center.y))
            s.frames(3000)
            assertTrue("fillStrike" in recording.calls, "calls ${recording.calls.distinct()}")
            assertTrue("strike" !in recording.calls)
            assertTrue("tilt" !in recording.calls, "a desktop never writes STRUCTURE from the pointer")
        }
    }

    @Test
    fun noFillStrikesOutsideShake() {
        val recording = RecordingActions()
        bellScene(recording, CowbellMode.TAP).use { s ->
            val bell = s.bell()
            s.move(Offset(bell.right - 1f, bell.center.y))
            s.frames(3000)
            assertTrue("fillStrike" !in recording.calls, "calls ${recording.calls.distinct()}")
        }
    }

    @Test
    fun shakePressKicksTheFillAndNeverStrikes() {
        val recording = RecordingActions()
        // Seeded: the press at the right edge tilts the fill by pointer (gravity toward -z) while the
        // kick throws it toward +z, so gravity opposes the kick and only beads that start near the
        // +z wall strike in time. An unlucky unseeded spawn (about 1 in 100) would have none.
        bellScene(recording, CowbellMode.SHAKE, fill = CowbellFill.BEADS, random = Random(SHAKE_PRESS_SEED)).use { s ->
            val bell = s.bell()
            s.frames(500)
            val at = Offset(bell.right - 20f, bell.center.y)
            s.press(at)
            s.release(at)
            s.frames(500)
            assertTrue("strike" !in recording.calls, "calls ${recording.calls.distinct()}")
            // Beads thrown against each other and the walls rattle, with no gravity at all.
            assertTrue("fillStrike" in recording.calls, "calls ${recording.calls.distinct()}")
        }
    }

    @Test
    fun motionPlatformsSendTiltOnceTheHoldingAngleSettles() {
        val recording = RecordingActions()
        val phone = TiltablePhone()
        bellScene(recording, CowbellMode.TAP, phone).use { s ->
            s.frames(1000)
            assertTrue("tilt" in recording.calls, "calls ${recording.calls.distinct()}")
        }
    }

    @Test
    fun shakeFramesDoNotRecomposeTheBell() {
        val recording = RecordingActions()
        var compositions = 0
        var mode by mutableStateOf(CowbellMode.SHAKE)
        CowbellScene(200, 300) {
            // A composed modifier runs each time the bell's node is composed again.
            val probe = Modifier.composed {
                compositions++
                Modifier
            }
            CowbellBell3D(mode = mode, actions = recording.actions, bend = { 0f }, modifier = probe.fillMaxSize())
        }.use { s ->
            s.move(Offset(190f, 150f))
            s.frames(500)
            val before = compositions
            assertTrue(before > 0)
            s.move(Offset(10f, 150f))
            s.frames(2000)
            assertEquals(before, compositions, "a frame recomposed the bell")
            assertTrue(s.scene.hasInvalidations(), "the frame loop stopped")

            // The probe does see a real recomposition.
            mode = CowbellMode.TAP
            s.frames(100)
            assertTrue(compositions > before, "the probe never fires")
        }
    }
}

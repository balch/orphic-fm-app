package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeDomeTest {
    private val radius = 12f

    // Every ring size either chrome draws; the bar and the rail share the 64dp one.
    private val ringSizes = listOf(BarRingSize, RailRingSize, RailCompactRingSize).distinct()

    @Test fun noDragNoTilt() = assertEquals(0f, domeTiltForDrag(0f))

    @Test fun halfwayToFortyIsHalfTilt() {
        assertEquals(0.5f, domeTiltForDrag(20f), 1e-6f)
        assertEquals(-0.5f, domeTiltForDrag(-20f), 1e-6f)
    }

    @Test fun fortyAndBeyondClamps() {
        assertEquals(1f, domeTiltForDrag(40f))
        assertEquals(1f, domeTiltForDrag(300f))
        assertEquals(-1f, domeTiltForDrag(-40f))
        assertEquals(-1f, domeTiltForDrag(-300f))
    }

    // The roll follows the finger: a right drag commits Next and rolls right, a left drag Previous rolls left.
    @Test fun nextRollsRightAndPreviousRollsLeft() {
        assertEquals(1, domeRollDirection(SwipeDecision.Next))
        assertEquals(-1, domeRollDirection(SwipeDecision.Previous))
        assertEquals(0, domeRollDirection(SwipeDecision.None))
    }

    @Test fun aCommitRollsTheWayItsDragTipped() {
        listOf(40f, -40f).forEach { drag ->
            val direction = domeRollDirection(swipeDecision(drag, 0f))
            assertEquals(domeTiltForDrag(drag), direction.toFloat(), "a ${drag}dp drag tipped one way and rolled the other")
        }
    }

    @Test fun theRollStartsWhereTheDragLeftIt() {
        assertEquals(-0.7f, domeRollTilt(0f, fromTilt = -0.7f, direction = -1), 1e-6f)
        assertEquals(0.4f, domeRollTilt(0f, fromTilt = 0.4f, direction = 1), 1e-6f)
    }

    @Test fun theRollReachesTheEdgeThenWrapsToTheOtherSide() {
        listOf(-1, 1).forEach { dir ->
            assertEquals(dir.toFloat(), domeRollTilt(0.5f, fromTilt = 0.6f * dir, direction = dir), 1e-6f)
            assertEquals(-dir.toFloat(), domeRollTilt(0.5001f, fromTilt = 0.6f * dir, direction = dir), 1e-3f)
        }
    }

    @Test fun theRollSettlesFlat() {
        assertEquals(0f, domeRollTilt(1f, fromTilt = -0.8f, direction = -1), 1e-6f)
        assertEquals(0f, domeRollTilt(1f, fromTilt = 0.3f, direction = 1), 1e-6f)
    }

    @Test fun eachHalfOfTheRollIsMonotonic() {
        listOf(-1, 1).forEach { dir ->
            val first = (0..50).map { domeRollTilt(it / 100f, fromTilt = 0.3f * dir, direction = dir) }
            val second = (51..100).map { domeRollTilt(it / 100f, fromTilt = 0.3f * dir, direction = dir) }
            first.zipWithNext().forEach { (a, b) -> assertTrue((b - a) * dir >= 0f, "first half turned back: $a -> $b") }
            second.zipWithNext().forEach { (a, b) -> assertTrue((b - a) * dir >= 0f, "second half turned back: $a -> $b") }
        }
    }

    // A commit starts near the edge; the first phase covers only what is left of the tip, so it never stalls.
    @Test fun theRollsFirstPhaseScalesWithWhatIsLeftToTip() {
        assertEquals(DomeRollOutMillis, domeRollOutMillis(fromTilt = 0f, direction = -1))
        assertEquals(DomeRollOutMillis / 2, domeRollOutMillis(fromTilt = 0.5f, direction = 1))
        val fromEdge = domeRollOutMillis(fromTilt = -1f, direction = -1)
        assertTrue(fromEdge in 1 until DomeRollOutMillis / 4, "a roll from the edge still needs a frame or two: $fromEdge")
        val durations = (0..10).map { domeRollOutMillis(fromTilt = -it / 10f, direction = -1) }
        durations.zipWithNext().forEach { (a, b) -> assertTrue(b <= a, "further tipped took longer: $durations") }
        assertEquals(DomeRollOutMillis, domeRollOutMillis(fromTilt = 0.6f, direction = -1))
    }

    // The contact shadow reads as elevation: flat, peeking out below the dome, and (drawn beneath the
    // ring) ending before the ring's centre line, so it never spills past the ring.
    @Test fun theShadowIsAContactEllipseInsideTheRing() {
        ringSizes.forEach { ring ->
            val domeRadius = (ring - domeInset(ring) * 2).value / 2
            val ringCentreLine = ring.value / 2 - ringInnerClearance(ring).value / 2
            val shadow = domeShadowExtent(domeRadius)
            assertTrue(shadow.height <= shadow.width * 0.5f, "shadow $shadow is not squashed")
            assertTrue(shadow.bottom > domeRadius, "a $ring shadow hides under the dome: $shadow")
            assertTrue(shadow.bottom < ringCentreLine, "a $ring shadow reaches ${shadow.bottom}dp, past the ring at ${ringCentreLine}dp")
            // Plus its drift at full tilt, a tenth of the dome's radius.
            assertTrue(shadow.right + 0.1f * domeRadius < ringCentreLine, "a $ring shadow spills sideways: $shadow")
        }
    }

    @Test fun theDomeClearsTheRingsInwardCrests() {
        ringSizes.forEach { ring ->
            assertTrue(domeInset(ring) > ringInnerClearance(ring), "a $ring dome's inset ${domeInset(ring)} collides with the ring")
        }
    }

    // Oversized against the 24dp tab icons: at least half as big again, on both surfaces.
    @Test fun theDomeIsOversizedNextToTheTabIcons() {
        listOf(BarRingSize, RailRingSize).distinct().forEach { ring ->
            val dome = ring - domeInset(ring) * 2
            assertTrue(dome >= 36.dp, "a $ring ring's dome is only $dome")
        }
    }

    @Test fun theRestingHighlightSitsUpLeft() {
        val rest = domeHighlightOffset(0f, radius)
        assertTrue(rest.x < 0f && rest.y < 0f, "resting highlight $rest is not up-left")
    }

    @Test fun theHighlightSlidesAgainstTheDrag() {
        val rest = domeHighlightOffset(0f, radius)
        assertTrue(domeHighlightOffset(0.5f, radius).x < rest.x, "a right tilt should push the light left")
        assertTrue(domeHighlightOffset(-0.5f, radius).x > rest.x, "a left tilt should push the light right")
        assertEquals(rest.y, domeHighlightOffset(1f, radius).y, 1e-6f)
    }

    @Test fun theHighlightStaysOnTheDome() {
        (-20..20).map { it / 10f }.forEach { tilt ->
            val o = domeHighlightOffset(tilt, radius)
            assertTrue(hypot(o.x, o.y) <= radius, "tilt $tilt put the light at $o, off a radius-$radius dome")
        }
    }

    @Test fun theGlyphLeansWithTheDragAndForeshortens() {
        assertEquals(0f, domeGlyphShift(0f, radius))
        assertTrue(domeGlyphShift(0.5f, radius) > 0f && domeGlyphShift(-0.5f, radius) < 0f)
        assertTrue(domeGlyphShift(5f, radius) <= radius * 0.3f + 1e-6f)
        assertEquals(1f, domeGlyphScaleX(0f))
        assertEquals(0.82f, domeGlyphScaleX(1f), 1e-6f)
        assertEquals(0.82f, domeGlyphScaleX(-3f), 1e-6f)
    }

    // Steps a scene holding only the motion 16ms at a time and records its tilt after each frame.
    private fun motionFrames(tv: Boolean = false, gesture: (DomeMotion) -> Unit): Pair<List<Float>, Boolean> {
        lateinit var motion: DomeMotion
        val scene = ImageComposeScene(10, 10) {
            CompositionLocalProvider(LocalTelevisionHardware provides tv) { motion = rememberDomeMotion() }
        }
        try {
            scene.render(0)
            gesture(motion)
            val tilts = (1..50).map { scene.render(it * 16_000_000L); motion.tilt }
            return tilts to scene.hasInvalidations()
        } finally {
            scene.close()
        }
    }

    @Test
    fun aCommittedSwipeRollsOnThroughTheEdgeAndSettles() {
        // A right drag to Next: tips right to the edge, then the new face comes round from the left.
        val (tilts, stillAnimating) = motionFrames { it.follow(30f); it.release(domeRollDirection(SwipeDecision.Next)) }
        val edge = tilts.indexOfFirst { it >= 0.99f }
        val newFace = tilts.indexOfFirst { it <= -0.5f }
        assertTrue(edge >= 0 && newFace > edge, "expected +1 then the far side coming round: $tilts")
        assertEquals(0f, tilts.last(), 1e-3f)
        assertFalse(stillAnimating, "a settled dome still asks for frames")
    }

    @Test
    fun aShortDragSpringsBackWithoutRolling() {
        val (tilts, stillAnimating) = motionFrames { it.follow(20f); it.release(0) }
        assertTrue(tilts.all { it > -0.5f && it <= 0.5f }, "a spring-back rolled: $tilts")
        assertEquals(0f, tilts.last(), 1e-3f)
        assertFalse(stillAnimating)
    }

    @Test
    fun televisionHardwareKeepsTheDomeStill() {
        val (tilts, stillAnimating) = motionFrames(tv = true) { it.follow(30f); it.release(domeRollDirection(SwipeDecision.Next)) }
        assertTrue(tilts.all { it == 0f }, "a TV dome moved: $tilts")
        assertFalse(stillAnimating)
    }

    // A counting draw in a layer around the ring stands in for the rail's glass or the bar.
    @Test
    fun aTracingRingAndATiltingDomeNeverReRecordWhatSurroundsThem() {
        var tilt by mutableFloatStateOf(0f)
        var surroundDraws = 0
        val scope = TestScopeFrame().apply { publish(sineWindow(cycles = 3f, amplitude = 0.5f), triggered = true) }
        val scene = ImageComposeScene(136, 136, Density(2f)) {
            CompositionLocalProvider(LocalScopeFeed provides ScopeFeed(scope) {}) {
                Box(Modifier.graphicsLayer().drawBehind { surroundDraws++ }) {
                    VibeTransportRing(paused = false, progress = 0.62f, domeTilt = { tilt })
                }
            }
        }
        try {
            scene.render(1_000_000_000L)
            val settled = scene.render(1_100_000_000L).encodeToData()!!.bytes
            val baseline = surroundDraws
            scope.publish(sineWindow(cycles = 5f, amplitude = 0.8f), triggered = true)
            val waved = scene.render(1_300_000_000L).encodeToData()!!.bytes
            assertFalse(settled.contentEquals(waved), "the trace never moved, so this proves nothing")
            tilt = 0.8f
            Snapshot.sendApplyNotifications()
            val tilted = scene.render(1_400_000_000L).encodeToData()!!.bytes
            assertFalse(waved.contentEquals(tilted), "the dome never tilted, so this proves nothing")
            assertEquals(baseline, surroundDraws, "a trace or tilt frame re-recorded the layer around the ring")
        } finally {
            scene.close()
        }
    }
}

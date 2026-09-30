package org.balch.orpheus.djapp.playlist

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EightBallRevealTest {
    /** With [syncsSheet] the sheet opens as DjAppScreen's does, and its open state comes back as [EightBallRevealState.sheetOpen]. */
    private class Stage(syncsSheet: Boolean = false) {
        var opened = 0
        var closed = 0
        val state: EightBallRevealState = EightBallRevealState(
            openSheet = { opened++; if (syncsSheet) state.sheetOpen = true },
            closeSheet = { closed++; if (syncsSheet) state.sheetOpen = false },
            random = Random(3),
        )
        private val scheduler = TestCoroutineScheduler()
        private var now = 0L
        val scene = ImageComposeScene(400, 800, Density(1f), StandardTestDispatcher(scheduler)) {
            OrpheusTheme { EightBallReveal(state) }
        }
        val dome = Rect(Offset(200f, 760f), 24f)
        val stageCentre = Offset(200f, 350f)

        init { state.stageBounds = Rect(0f, 0f, 400f, 700f) }

        fun frames(ms: Long) = repeat((ms / 16).toInt()) {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            scene.render(now * 1_000_000)
        }

        // Any of the "8" disc's white in column [x] of the current frame.
        fun whiteIn(x: Int, ys: IntRange): Boolean {
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            return ys.any { y -> pixels[x, y].let { it.red > 0.9f && it.green > 0.9f && it.blue > 0.9f } }
        }

        /** The alpha of each pixel in a 9x9 square around [at]. */
        fun pixelsAround(at: Offset): List<Int> {
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            return (-4..4).flatMap { dx -> (-4..4).map { dy -> (pixels[at.x.toInt() + dx, at.y.toInt() + dy].alpha * 255).toInt() } }
        }

        val elapsedMs get() = now

        fun tap(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = now, type = PointerType.Touch, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = now + 20, type = PointerType.Touch, button = PointerButton.Primary)
        }

        fun close() = scene.close()
    }

    @Test
    fun theRevealOpensTheSheetOnceThenSettles() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            stage.frames(800)
            assertEquals(0, stage.opened, "the sheet waited for the phrase")
            stage.frames(4_000)
            assertEquals(1, stage.opened)
            assertIs<RevealPhase.Idle>(stage.state.phase)
        } finally { stage.close() }
    }

    @Test
    fun aTapOnTheBallSkipsToTheSheet() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            stage.frames(900)
            stage.tap(stage.stageCentre)
            stage.frames(32)
            assertEquals(1, stage.opened)
            stage.frames(1_000)
            assertIs<RevealPhase.Idle>(stage.state.phase)
            assertEquals(1, stage.opened)
        } finally { stage.close() }
    }

    @Test
    fun aTapMidTurnSinksWithTheFaceWhole() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            // Right of the "8" glyph, inside its disc: white only while the face is near full size.
            val x = stage.stageCentre.x.toInt() + 25
            val ys = 250..500
            var whole = 0
            while (whole < 2 || stage.whiteIn(x, ys)) {
                stage.frames(16)
                if (stage.whiteIn(x, ys)) whole++
                check(stage.elapsedMs < 3_000) { "the face never turned" }
            }
            stage.tap(stage.stageCentre)
            stage.frames(32)
            assertTrue(stage.whiteIn(x, ys), "the ball sinks with its face whole, not squashed mid-turn")
            stage.frames(1_000)
            assertEquals(1, stage.opened)
            assertIs<RevealPhase.Idle>(stage.state.phase)
        } finally { stage.close() }
    }

    @Test
    fun aSecondLaunchDuringTheRevealIsIgnored() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            val phrase = stage.state.phrase
            stage.state.launch(stage.dome)
            assertEquals(phrase, stage.state.phrase)
            stage.frames(4_800)
            assertEquals(1, stage.opened)
        } finally { stage.close() }
    }

    @Test
    fun eachRevealShowsANewPhrase() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            val first = stage.state.phrase
            stage.frames(4_800)
            stage.state.launch(stage.dome)
            assertNotEquals(first, stage.state.phrase)
        } finally { stage.close() }
    }
    @Test
    fun aFullRevealWithTheSheetOpenEndsOpenAndStays() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(4_800)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertTrue(stage.state.showsBallInRing)
            assertTrue(stage.state.hidesDome)
            stage.frames(2_000)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertEquals(1, stage.opened)
        } finally { stage.close() }
    }

    @Test
    fun theSheetClosingWhileOpenGoesIdle() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(4_800)
            stage.state.sheetOpen = false
            assertIs<RevealPhase.Idle>(stage.state.phase)
            assertFalse(stage.state.showsBallInRing)
            assertFalse(stage.state.hidesDome)
        } finally { stage.close() }
    }

    @Test
    fun theSheetClosingDuringTheSinkEndsIdle() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(900)
            stage.tap(stage.stageCentre)
            stage.frames(32)
            assertIs<RevealPhase.Sinking>(stage.state.phase)
            stage.state.sheetOpen = false
            stage.frames(1_000)
            assertIs<RevealPhase.Idle>(stage.state.phase)
        } finally { stage.close() }
    }

    @Test
    fun theSheetClosingWhileShowingStillEndsIdle() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            stage.frames(300)
            stage.state.sheetOpen = true
            stage.state.sheetOpen = false
            assertIs<RevealPhase.Showing>(stage.state.phase, "the reveal plays on")
            stage.frames(4_800)
            assertIs<RevealPhase.Idle>(stage.state.phase)
        } finally { stage.close() }
    }

    @Test
    fun openNowGoesStraightToOpen() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        state.openNow()
        assertIs<RevealPhase.Open>(state.phase)
        assertTrue(state.showsBallInRing)
    }

    @Test
    fun aSheetOpenedElsewhereShowsTheBallInTheRing() {
        // Android restores the open sheet after a rotation, with no reveal.
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        state.sheetOpen = true
        assertIs<RevealPhase.Open>(state.phase)
    }

    @Test
    fun closeClosesTheSheetAndTheDomeReturns() {
        var closed = 0
        val state = EightBallRevealState(openSheet = {}, closeSheet = { closed++ }, random = Random(3))
        state.close()
        assertEquals(0, closed, "nothing to close while idle")
        state.openNow()
        state.close()
        assertEquals(1, closed)
        assertIs<RevealPhase.Idle>(state.phase)
    }

    @Test
    fun theOverlayLeavesTheBallToTheRingOnceOpen() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(900)
            stage.tap(stage.stageCentre)
            // Near the end of the sink the overlay's ball has all but reached the dome.
            stage.frames(256)
            assertIs<RevealPhase.Sinking>(stage.state.phase)
            assertTrue(stage.pixelsAround(stage.dome.center).any { it > 0 })
            stage.frames(200)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertTrue(stage.pixelsAround(stage.dome.center).all { it == 0 }, "the ring draws the ball now")
        } finally { stage.close() }
    }
}

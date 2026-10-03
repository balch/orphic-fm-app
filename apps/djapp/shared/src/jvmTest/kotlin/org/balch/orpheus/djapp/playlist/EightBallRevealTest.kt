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
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

        /**
         * Renders the next frame: the "8" disc's white width and the height of the ∞'s black strokes
         * inside it, both within [within]. The glyph is wider than tall, so its height grows as the ball tilts.
         */
        fun discWidthAndGlyphHeight(within: Rect): Pair<Float, Float>? {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            var left = Int.MAX_VALUE
            var right = -1
            var top = Int.MAX_VALUE
            var bottom = -1
            for (y in within.top.toInt() until within.bottom.toInt()) for (x in within.left.toInt() until within.right.toInt()) {
                val c = pixels[x, y]
                if (c.alpha > 0.9f && c.red > 0.9f && c.green > 0.9f && c.blue > 0.9f) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            if (right < 0) return null
            val centre = Offset((left + right) / 2f, (top + bottom) / 2f)
            val radius = (right - left) / 2f - 3f
            var gTop = Int.MAX_VALUE
            var gBottom = -1
            for (y in top..bottom) for (x in left..right) {
                val c = pixels[x, y]
                if (c.alpha > 0.9f && c.red < 0.15f && c.green < 0.15f && c.blue < 0.15f && (Offset(x.toFloat(), y.toFloat()) - centre).getDistance() < radius) {
                    gTop = minOf(gTop, y); gBottom = maxOf(gBottom, y)
                }
            }
            return if (gBottom < 0) null else (right - left + 1f) to (gBottom - gTop + 1f)
        }

        /** The alpha of each pixel in a 9x9 square around [at]. */
        fun pixelsAround(at: Offset): List<Int> {
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            return (-4..4).flatMap { dx -> (-4..4).map { dy -> (pixels[at.x.toInt() + dx, at.y.toInt() + dy].alpha * 255).toInt() } }
        }

        val elapsedMs get() = now

        /** Renders the next frame: the box around the phrase's light-blue text within [within], if any. */
        fun phraseBox(within: Rect, minAlpha: Float = 0.5f): Rect? {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1
            for (y in within.top.toInt() until within.bottom.toInt()) for (x in within.left.toInt() until within.right.toInt()) {
                val c = pixels[x, y]
                // The phrase's PhraseBlue: light in red and blue, unlike the ball's greys, the die's blue and the backing.
                if (c.alpha < minAlpha || c.red < 0.45f || c.blue < 0.7f) continue
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
            return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
        }

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
            stage.frames(6_000)
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
            stage.frames(6_000)
            assertEquals(1, stage.opened)
        } finally { stage.close() }
    }

    @Test
    fun eachRevealShowsANewPhrase() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            val first = stage.state.phrase
            stage.frames(6_000)
            stage.state.launch(stage.dome)
            assertNotEquals(first, stage.state.phrase)
        } finally { stage.close() }
    }
    @Test
    fun aFullRevealWithTheSheetOpenEndsOpenAndStays() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(6_000)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertTrue(stage.state.showsBallInRing)
            assertEquals(0f, stage.state.ringTurn, "the ball, not the dome, is what the ring shows")
            stage.frames(2_000)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertEquals(1, stage.opened)
        } finally { stage.close() }
    }

    @Test
    fun theSheetClosingWhileOpenTurnsTheBallBackThenGoesIdle() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(6_000)
            stage.state.sheetOpen = false
            assertIs<RevealPhase.Closing>(stage.state.phase, "the ball turns back into the dome")
            assertTrue(stage.state.showsBallInRing)
            stage.frames(600)
            assertIs<RevealPhase.Idle>(stage.state.phase)
            assertFalse(stage.state.showsBallInRing)
            assertEquals(1f, stage.state.ringTurn, "the dome, not the ball, is what the ring shows")
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
            stage.frames(6_000)
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
    fun closeClosesTheSheetAndTurnsTheBallBackIntoTheDome() {
        var closed = 0
        val state = EightBallRevealState(openSheet = {}, closeSheet = { closed++ }, random = Random(3))
        state.close()
        assertEquals(0, closed, "nothing to close while idle")
        state.openNow()
        state.close()
        assertEquals(1, closed)
        assertIs<RevealPhase.Closing>(state.phase)
        assertTrue(state.showsBallInRing, "the ring keeps the ball while it turns")
        assertFalse(state.isOpen, "the sheet is closing: the dome's labels are back")
        assertEquals(0f, state.ringTurn, "the turn starts on the ball")
        state.close()
        assertEquals(1, closed, "a second tap while it turns closes nothing more")
        state.finish()
        assertIs<RevealPhase.Idle>(state.phase)
        assertEquals(1f, state.ringTurn, "the turn ends on the dome")
    }

    @Test
    fun theSheetClosingElsewhereTurnsTheBallBackToo() {
        // The scrim, Back or another sheet: the ring hears of it as sheetOpen, and turns as it does for its own tap.
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        state.sheetOpen = true
        assertIs<RevealPhase.Open>(state.phase)
        state.sheetOpen = false
        assertIs<RevealPhase.Closing>(state.phase)
    }

    @Test
    fun aLongPressWhileTheBallTurnsBackIsIgnored() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        state.openNow()
        state.close()
        state.launch(Rect(Offset(200f, 760f), 24f))
        assertIs<RevealPhase.Closing>(state.phase)
    }

    @Test
    fun theRingTurnIsTheBallWhileOpenAndTheDomeWhenIdle() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        assertEquals(1f, state.ringTurn)
        state.launch(Rect(Offset(200f, 760f), 24f))
        assertEquals(0f, state.ringTurn, "the reveal's ball stands in for the dome")
        state.handOver()
        assertEquals(0f, state.ringTurn)
        state.openNow()
        assertEquals(0f, state.ringTurn)
        state.close()
        state.finish()
        state.openNow()
        assertEquals(0f, state.ringTurn, "a second open starts on the ball again, not where the last turn ended")
    }

    @Test
    fun theHeadersPhraseWaitsForTheRevealThenFadesInAsItLands() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        assertEquals(1f, state.headerPhraseAlpha, "no reveal, no wait")
        state.launch(Rect(Offset(200f, 760f), 24f))
        assertEquals(0f, state.headerPhraseAlpha, "the phrase is up on the stage, on its way")
        state.handOver()
        assertEquals(0f, state.headerPhraseAlpha, "and taking off")
        state.flight = 0.5f
        assertEquals(0f, state.headerPhraseAlpha, "and mid-flight")
        state.flight = 0.9f
        assertEquals(0.5f, state.headerPhraseAlpha, 1e-3f, "the header's copy fades in over the last of the flight")
        state.flight = 1f
        assertEquals(1f, state.headerPhraseAlpha, "landed")
        state.sheetOpen = true
        state.finish()
        assertEquals(1f, state.headerPhraseAlpha)
        state.openNow()
        assertEquals(1f, state.headerPhraseAlpha, "a sheet opened with no reveal shows its phrase at once")
    }

    @Test
    fun theNamePillFadesOutAsTheBallRises() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        assertEquals(1f, state.nameAlpha)
        assertTrue(state.namePresent)
        state.launch(Rect(Offset(200f, 760f), 24f))
        assertEquals(1f, state.nameAlpha, "nothing has risen yet")
        state.travel = 0.25f
        assertEquals(0.75f, state.nameAlpha, 1e-3f)
        state.travel = 1f
        assertEquals(0f, state.nameAlpha, "the ball is up: the title is gone, well before the sheet opens")
        state.travel = 1.04f
        assertEquals(0f, state.nameAlpha, "the spring's overshoot fades nothing below nothing")
        assertTrue(state.namePresent, "still composed while it fades")
        state.handOver()
        assertFalse(state.namePresent)
        state.sheetOpen = true
        state.finish()
        assertEquals(0f, state.nameAlpha, "and it stays gone while the playlist is open")
        assertFalse(state.namePresent)
    }

    @Test
    fun theNamePillFadesBackInAsTheDomeTurnsIn() {
        val state = EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(3))
        state.openNow()
        assertEquals(0f, state.nameAlpha)
        state.close()
        assertTrue(state.namePresent, "composed again for the fade")
        assertEquals(0f, state.nameAlpha, "the turn has not begun")
        state.flip = 0.5f
        assertEquals(0.5f, state.nameAlpha, 1e-3f)
        state.finish()
        assertEquals(1f, state.nameAlpha)
    }

    @Test
    fun aFaceTurnsEdgeOnByTheMidpointThenInFromTheOtherSide() {
        assertEquals(0f, turnDegrees(0f))
        assertEquals(45f, turnDegrees(0.25f), 1e-3f)
        assertEquals(90f, turnDegrees(0.4999f), 0.1f)
        assertEquals(-90f, turnDegrees(0.5f), 1e-3f)
        assertEquals(-45f, turnDegrees(0.75f), 1e-3f)
        assertEquals(0f, turnDegrees(1f), 1e-3f)
    }

    @Test
    fun theBallTurnsBackIntoTheDomeAndTheDomeArrivesWhole() {
        val stage = Stage(syncsSheet = true)
        try {
            stage.state.launch(stage.dome)
            stage.frames(6_000)
            assertIs<RevealPhase.Open>(stage.state.phase)
            stage.state.close()
            var last = 0f
            repeat(24) {
                stage.frames(16)
                assertTrue(stage.state.ringTurn >= last, "the turn never runs backwards: $last then ${stage.state.ringTurn}")
                last = stage.state.ringTurn
            }
            stage.frames(200)
            assertIs<RevealPhase.Idle>(stage.state.phase)
            assertEquals(1f, stage.state.ringTurn)
        } finally { stage.close() }
    }

    @Test
    fun theBallShakesBeforeItTurnsOver() {
        val stage = Stage()
        try {
            stage.state.launch(stage.dome)
            val around = Rect(Offset(stage.stageCentre.x, stage.stageCentre.y), 60f)
            var shortest = Float.MAX_VALUE
            var tallest = 0f
            while (stage.state.phase is RevealPhase.Showing && stage.elapsedMs < 2_000) {
                val (disc, glyph) = stage.discWidthAndGlyphHeight(around) ?: continue
                // Only the ball at its full size and face-on: the disc is 67px across, and the glyph then only tilts.
                if (disc < 66f) continue
                shortest = minOf(shortest, glyph)
                tallest = maxOf(tallest, glyph)
            }
            // A ball 13° off level lifts its ∞'s ends: about 10px taller than level at this size.
            assertTrue(tallest - shortest >= 5f, "the ∞ stood between $shortest and $tallest px tall: the ball never tilted")
        } finally { stage.close() }
    }

    @Test
    fun thePhraseRisesFromTheDieAndEnlargesToBeRead() {
        val stage = Stage()
        try {
            // Above the ball, which fills the stage's centre out to 80px.
            val above = Rect(0f, 60f, 400f, 250f)
            stage.state.launch(stage.dome)
            val phrase = "“${stage.state.phrase}”"
            repeat(60) { assertNull(stage.phraseBox(above), "the phrase waits for the ball to turn: at ${stage.elapsedMs}ms") }
            var widest = 0f
            while (stage.state.phase is RevealPhase.Showing) {
                stage.phraseBox(above)?.let { widest = maxOf(widest, it.width) }
                check(stage.elapsedMs < 6_000) { "the reveal never handed over" }
            }
            // The die's own text was 7.5px tall, about 4.5px a letter. Read from the die it is readable from arm's length.
            assertTrue(widest > phrase.length * 8f, "the phrase spans $widest px above the ball; $phrase needs more than ${phrase.length * 8f}")
        } finally { stage.close() }
    }

    @Test
    fun thePhraseFliesToTheHeadersLineAndLandsOnIt() {
        val stage = Stage(syncsSheet = true)
        try {
            // A header line well clear of the ball and the dome: its left edge and its vertical centre.
            stage.state.anchorPhrase(40f, 600f)
            stage.state.launch(stage.dome)
            val band = Rect(0f, 520f, 400f, 680f)
            var landed: Rect? = null
            while (true) {
                check(stage.elapsedMs < 8_000) { "the reveal never settled" }
                val box = stage.phraseBox(band, minAlpha = 0.2f)
                // Read after the render: a frame the phase is still Sinking after was drawn flying. The last
                // frames crossfade into the header's own phrase, so the last one still drawn is the one kept.
                if (stage.state.phase is RevealPhase.Open) break
                if (stage.state.phase is RevealPhase.Sinking && box != null) landed = box
            }
            val box = assertNotNull(landed, "the phrase was in flight when the sheet's header took over")
            // The last frames crossfade into the header's own copy, so the last drawn is a line's height short of it.
            assertEquals(40f, box.left, 8f, "it lands on the header line's left edge: $box")
            assertEquals(600f, box.center.y, 20f, "and on its centre line: $box")
            assertTrue(box.width < 260f, "shrunk to the header's own size: $box")
            assertEquals(1f, stage.state.headerPhraseAlpha, "and the header's own copy is fully in when the sheet holds the ball")
        } finally { stage.close() }
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
            // The phrase's flight to the header runs on after the sink: the ball waits in the dome for it.
            stage.frames(1_000)
            assertIs<RevealPhase.Open>(stage.state.phase)
            assertTrue(stage.pixelsAround(stage.dome.center).all { it == 0 }, "the ring draws the ball now")
        } finally { stage.close() }
    }
}

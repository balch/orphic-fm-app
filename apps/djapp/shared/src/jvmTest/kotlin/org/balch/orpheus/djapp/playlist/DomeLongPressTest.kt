package org.balch.orpheus.djapp.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.djapp.BarRingSize
import org.balch.orpheus.djapp.TransportPadding
import org.balch.orpheus.djapp.VibeTransportItem
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DomeLongPressTest {
    /** [pill]: the phone bar's and the dock's dome, its name in a glass pill above the ring; otherwise the rail's, the name under it. */
    private class Dome(tv: Boolean = false, provided: Boolean = true, reveal: Boolean = false, pill: Boolean = false) {
        var toggles = 0
        var opened = 0
        var closed = 0
        var nexts = 0
        // Opening and closing come back as sheetOpen, as DjAppScreen's active sheet does.
        val ball: EightBallRevealState = EightBallRevealState(
            openSheet = { opened++; ball.sheetOpen = true },
            closeSheet = { closed++; ball.sheetOpen = false },
            random = Random(1),
        )
        private val scheduler = TestCoroutineScheduler()
        private var now = 0L
        val scene = ImageComposeScene(300, 240, Density(1f), StandardTestDispatcher(scheduler)) {
            OrpheusTheme {
                CompositionLocalProvider(
                    LocalEightBall provides ball.takeIf { provided },
                    LocalTelevisionHardware provides tv,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        VibeTransportItem(
                            name = "Dog House", previousName = "Rust Belt", nextName = "Stay Asleep",
                            progress = 0.3f, paused = false,
                            onTogglePlayback = { toggles++ }, onNext = { nexts++ }, onPrevious = {},
                            namePill = pill,
                        )
                    }
                    if (reveal) EightBallReveal(ball)
                }
            }
        }

        init { idle(32) }

        fun idle(ms: Long) {
            repeat((ms / 16).toInt()) {
                now += 16
                scheduler.advanceTimeBy(16)
                scheduler.runCurrent()
                scene.render(now * 1_000_000)
            }
        }

        private fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            walk(scene.semanticsOwners.first().rootSemanticsNode)
            return out
        }

        val dome: SemanticsNode get() = assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null })
        val centre: Offset get() = dome.boundsInRoot.center

        fun pointer(type: PointerEventType, at: Offset) {
            val button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null
            scene.sendPointerEvent(type, at, timeMillis = now, type = PointerType.Touch, button = button)
        }

        fun hold(ms: Long) { pointer(PointerEventType.Press, centre); idle(ms); pointer(PointerEventType.Release, centre); idle(32) }

        /** Every line of text on the bar, the pill's too, which sits outside the dome's node. */
        val barTexts: List<String> get() {
            val out = mutableListOf<String>()
            fun walk(n: SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().forEach { out += it.text }
                n.children.forEach(::walk)
            }
            walk(scene.semanticsOwners.first().unmergedRootSemanticsNode)
            return out
        }

        /** Drags [dp] right from the ring's centre and holds there, short of a release. */
        fun dragAndHold(dp: Int) {
            val from = centre
            pointer(PointerEventType.Press, from)
            (4..dp step 4).forEach { pointer(PointerEventType.Move, from + Offset(it.toFloat(), 0f)); idle(16) }
            idle(32)
        }

        // Far enough to commit past a touch's slop.
        fun swipeRight() {
            val from = centre
            pointer(PointerEventType.Press, from)
            (4..80 step 4).forEach { pointer(PointerEventType.Move, from + Offset(it.toFloat(), 0f)); idle(16) }
            pointer(PointerEventType.Release, from + Offset(80f, 0f))
            idle(32)
        }

        // The ring's centre: the transport's label sits below the ring, inside the node.
        val centreOfRing: Offset get() = dome.boundsInRoot.let { Offset(it.center.x, it.top + TransportPadding.value + BarRingSize.value / 2) }

        private fun Color.isDieBlue() =
            abs(red - 0x2F / 255f) < 0.08f && abs(green - 0x4F / 255f) < 0.08f && abs(blue - 0xD8 / 255f) < 0.08f

        /** Whether the pixel at [at] is the die's blue. */
        fun isDieBlue(at: Offset): Boolean =
            scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()[at.x.toInt(), at.y.toInt()].isDieBlue()

        /** Renders the next frame: the box around every pixel of the die's blue in it, if any. */
        fun dieBounds(): Rect? {
            now += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            var box: Rect? = null
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                if (!pixels[x, y].isDieBlue()) continue
                val px = Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f)
                box = box?.let { Rect(minOf(it.left, px.left), minOf(it.top, px.top), maxOf(it.right, px.right), maxOf(it.bottom, px.bottom)) } ?: px
            }
            return box
        }

        val elapsedMs get() = now

        /** Where the pill's name sits, from the unmerged tree: it stays in it, hidden from accessibility, while composed. */
        val pillRegion: Rect
            get() {
                var found: SemanticsNode? = null
                fun walk(n: SemanticsNode) {
                    if (found == null && n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Dog House" } == true) found = n
                    n.children.forEach(::walk)
                }
                walk(scene.semanticsOwners.first().unmergedRootSemanticsNode)
                return assertNotNull(found, "the pill is not composed").boundsInRoot
            }

        /** The alpha drawn within [within] in the current frame: what is left of the pill's name as it fades. */
        fun inkIn(within: Rect): Float {
            val pixels = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()
            var sum = 0f
            for (y in within.top.toInt() until within.bottom.toInt()) for (x in within.left.toInt() until within.right.toInt()) sum += pixels[x, y].alpha
            return sum
        }

        /** The playlist is open: the ring holds the ball. */
        fun open() { ball.openNow(); idle(32) }

        val texts: List<String> get() = dome.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        fun close() = scene.close()
    }

    @Test
    fun aLongPressLaunchesTheRevealFromTheDome() {
        val dome = Dome()
        try {
            val centre = dome.centre
            dome.hold(800)
            val showing = assertIs<RevealPhase.Showing>(dome.ball.phase)
            assertEquals(centre.x, showing.from.center.x, 2f)
        } finally { dome.close() }
    }

    @Test
    fun aLongPressNeverToggles() {
        val dome = Dome()
        try {
            dome.hold(800)
            assertEquals(0, dome.toggles)
        } finally { dome.close() }
    }

    @Test
    fun aTapStillToggles() {
        val dome = Dome()
        try {
            dome.hold(48)
            assertEquals(1, dome.toggles)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
        } finally { dome.close() }
    }

    @Test
    fun aDragNeverOpensThePlaylist() {
        val dome = Dome()
        try {
            val from = dome.centre
            dome.pointer(PointerEventType.Press, from)
            (4..48 step 4).forEach { dome.pointer(PointerEventType.Move, from + Offset(it.toFloat(), 0f)); dome.idle(16) }
            dome.idle(800)
            dome.pointer(PointerEventType.Release, from + Offset(48f, 0f))
            dome.idle(32)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
            assertEquals(0, dome.toggles)
        } finally { dome.close() }
    }

    @Test
    fun theDomeOffersAPlaylistActionThatOpensTheSheet() {
        val dome = Dome()
        try {
            val action = dome.dome.config.getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == "Playlist" }
            assertNotNull(action).action()
            assertEquals(1, dome.opened)
            assertIs<RevealPhase.Open>(dome.ball.phase, "the action opens the sheet with no reveal, the ball straight in the ring")
        } finally { dome.close() }
    }

    @Test
    fun withoutAnEightBallThereIsNoPlaylistAction() {
        val dome = Dome(provided = false)
        try {
            assertNull(dome.dome.config.getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == "Playlist" })
        } finally { dome.close() }
    }

    @Test
    fun whileOpenATapClosesThePlaylistAndNeverToggles() {
        val dome = Dome(reveal = true)
        try {
            dome.open()
            dome.hold(48)
            assertEquals(1, dome.closed)
            assertEquals(0, dome.toggles)
            assertIs<RevealPhase.Closing>(dome.ball.phase, "the ball turns back into the dome")
            dome.idle(600)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
            assertEquals(0, dome.toggles)
        } finally { dome.close() }
    }

    @Test
    fun aSecondTapWhileTheBallTurnsBackNeitherClosesNorToggles() {
        val dome = Dome(reveal = true)
        try {
            dome.open()
            dome.hold(48)
            // Still turning: a double tap is not a pause.
            dome.hold(48)
            assertEquals(1, dome.closed)
            assertEquals(0, dome.toggles)
            dome.idle(600)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
        } finally { dome.close() }
    }

    @Test
    fun theRingTurnsFromTheBallToTheDomeRatherThanSnapping() {
        val dome = Dome(reveal = true)
        try {
            dome.open()
            val whole = assertNotNull(dome.dieBounds(), "the ball sits in the ring").width
            dome.pointer(PointerEventType.Press, dome.centre)
            dome.pointer(PointerEventType.Release, dome.centre)
            var narrowest = whole
            var frames = 0
            var last: Rect? = null
            while (dome.ball.phase !is RevealPhase.Idle) {
                check(++frames < 60) { "the ball never finished turning" }
                last = dome.dieBounds()
                last?.let { narrowest = minOf(narrowest, it.width) }
            }
            assertTrue(frames > 4, "it took $frames frames: more than a snap")
            assertTrue(narrowest < whole * 0.35f, "the ball narrowed to $narrowest of $whole as it turned edge-on")
            assertNull(dome.dieBounds(), "the dome is back: no die left in the ring")
        } finally { dome.close() }
    }

    // The pill layouts: nothing rides above the ball, neither the phrase nor the vibe's name.
    @Test
    fun whileOpenAPillLayoutDrawsNoPillOverTheBall() {
        val dome = Dome(pill = true)
        try {
            assertTrue("Dog House" in dome.barTexts, "sanity: closed, the pill names the vibe: ${dome.barTexts}")
            dome.open()
            assertFalse("Dog House" in dome.barTexts, "the pill is gone: ${dome.barTexts}")
            assertFalse(dome.ball.phrase in dome.barTexts, "the phrase is not on the bar: ${dome.barTexts}")
            // Shuffle's new phrase stays off the bar as well.
            val next = dome.ball.roll()
            dome.idle(32)
            assertFalse(next in dome.barTexts, "the bar reads ${dome.barTexts}")
        } finally { dome.close() }
    }

    // The reveal's ball stands in for the dome, and the vibe's name has no business over it: it fades as the ball rises.
    @Test
    fun theNamePillFadesOutAsTheBallRisesNotWhenTheSheetOpens() {
        val dome = Dome(pill = true)
        try {
            val pill = dome.pillRegion
            val full = dome.inkIn(pill)
            assertTrue(full > 0f, "sanity: the pill is drawn")
            // No overlay here: the test sets how far the ball has risen, so nothing else draws in the pill's box.
            dome.ball.launch(Rect(dome.centre, 24f))
            dome.ball.travel = 0.5f
            dome.idle(32)
            assertEquals(0.5f * full, dome.inkIn(pill), 0.15f * full, "half risen, the name is half faded")
            dome.ball.travel = 1f
            dome.idle(32)
            assertEquals(0f, dome.inkIn(pill), "risen, it is gone")
            assertIs<RevealPhase.Showing>(dome.ball.phase, "and the sheet is yet to open")
        } finally { dome.close() }
    }

    @Test
    fun theNamePillFadesBackInAsTheBallTurnsIntoTheDome() {
        val dome = Dome(pill = true)
        try {
            val pill = dome.pillRegion
            val full = dome.inkIn(pill)
            dome.open()
            // No overlay here to run the turn: the test sets how far it has come.
            dome.ball.close()
            dome.idle(32)
            assertEquals(0f, dome.inkIn(pill), 0.05f * full, "the turn has not begun")
            dome.ball.flip = 0.5f
            dome.idle(32)
            assertEquals(0.5f * full, dome.inkIn(pill), 0.15f * full, "halfway, the name is half in")
            dome.ball.flip = 1f
            dome.idle(32)
            assertEquals(full, dome.inkIn(pill), 0.1f * full, "turned, the name is whole")
        } finally { dome.close() }
    }

    @Test
    fun whileOpenAPillLayoutDrawsNoPeekPillEither() {
        val dome = Dome(pill = true)
        try {
            dome.dragAndHold(60)
            assertTrue(dome.barTexts.any { it.startsWith("Stay Asleep") }, "sanity: closed, the drag peeks: ${dome.barTexts}")
        } finally { dome.close() }
        val open = Dome(pill = true)
        try {
            open.open()
            open.dragAndHold(60)
            assertTrue(open.barTexts.none { it.contains("Stay Asleep") || it.contains("Dog House") }, "the bar reads ${open.barTexts}")
        } finally { open.close() }
    }

    @Test
    fun whileOpenATapOnAPillLayoutsDomeClosesThePlaylist() {
        val dome = Dome(pill = true, reveal = true)
        try {
            dome.open()
            dome.hold(48)
            assertEquals(1, dome.closed)
            assertEquals(0, dome.toggles)
            assertTrue("Dog House" in dome.barTexts, "closing, the pill is back at once: ${dome.barTexts}")
            dome.idle(600)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
            assertTrue("Dog House" in dome.barTexts, "closed, the pill is back: ${dome.barTexts}")
        } finally { dome.close() }
    }

    @Test
    fun whileOpenASwipeStillSkips() {
        val dome = Dome()
        try {
            dome.open()
            dome.swipeRight()
            assertEquals(1, dome.nexts)
            assertEquals(0, dome.closed)
            assertIs<RevealPhase.Open>(dome.ball.phase)
        } finally { dome.close() }
    }

    @Test
    fun whileOpenALongPressDoesNothingNew() {
        val dome = Dome()
        try {
            dome.open()
            dome.hold(800)
            assertIs<RevealPhase.Open>(dome.ball.phase)
            assertEquals(0, dome.closed)
            assertEquals(0, dome.toggles)
        } finally { dome.close() }
    }

    @Test
    fun whileOpenTheRingShowsTheBall() {
        val dome = Dome()
        try {
            val centre = dome.centreOfRing
            assertFalse(dome.isDieBlue(centre), "play/pause first")
            dome.open()
            assertTrue(dome.isDieBlue(centre), "the die's face sits where the dome was")
        } finally { dome.close() }
    }

    @Test
    fun theSinkEndsWhereTheRingBallSits() {
        val dome = Dome(reveal = true)
        try {
            dome.hold(800)
            var sunk: Rect? = null
            while (true) {
                check(dome.elapsedMs < 6_000) { "the reveal never settled" }
                val frame = dome.dieBounds()
                // Read after the render: a frame the phase is still Sinking after was drawn sinking.
                if (dome.ball.phase is RevealPhase.Open) break
                if (dome.ball.phase is RevealPhase.Sinking) sunk = frame
            }
            val resting = assertNotNull(dome.dieBounds())
            val last = assertNotNull(sunk)
            listOf(last.left to resting.left, last.top to resting.top, last.right to resting.right, last.bottom to resting.bottom)
                .forEach { (a, b) -> assertEquals(b, a, 1f, "the ball's last sink frame $last against the ring's $resting") }
        } finally { dome.close() }
    }

    @Test
    fun whileOpenTheDomeReadsClosePlaylist() {
        val dome = Dome()
        try {
            dome.open()
            assertEquals(listOf("Close playlist"), dome.dome.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals("Close playlist", dome.dome.config.getOrNull(SemanticsActions.OnClick)?.label)
            val actions = dome.dome.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
            assertEquals(listOf("Next vibe", "Previous vibe"), actions, "no Playlist action while it is open")
        } finally { dome.close() }
    }

    // The rail's label sits under the ring, part of the dome's node, and keeps naming the vibe.
    @Test
    fun whileOpenTheRailKeepsTheVibesNameUnderTheRing() {
        val dome = Dome()
        try {
            assertTrue("Dog House" in dome.texts)
            dome.open()
            assertTrue("Dog House" in dome.texts, "the label reads ${dome.texts}")
            assertFalse(dome.ball.phrase in dome.texts, "the phrase is not the label: ${dome.texts}")
            val next = dome.ball.roll()
            dome.idle(32)
            assertFalse(next in dome.texts)
            assertTrue("Dog House" in dome.texts)
        } finally { dome.close() }
    }

    @Test
    fun closedTheDomeIsPlayPauseAndTheNameAgain() {
        val dome = Dome()
        try {
            dome.open()
            dome.ball.sheetOpen = true
            dome.ball.sheetOpen = false
            dome.idle(32)
            assertEquals(listOf("Pause, Dog House"), dome.dome.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("Dog House" in dome.texts)
        } finally { dome.close() }
    }

    @Test
    fun televisionHardwareGetsNoLongPress() {
        val dome = Dome(tv = true)
        try {
            dome.hold(800)
            assertIs<RevealPhase.Idle>(dome.ball.phase)
        } finally { dome.close() }
    }
}

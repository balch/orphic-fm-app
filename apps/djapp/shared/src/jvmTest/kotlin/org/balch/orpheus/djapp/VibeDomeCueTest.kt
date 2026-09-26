package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.playback.VibeMove
import org.balch.orpheus.features.pulsar.playback.VibeMoveKind
import org.balch.orpheus.features.pulsar.playback.VibeMoveOrigin
import org.balch.orpheus.features.pulsar.playback.VibeRequest
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dome moving by itself: it rolls with every vibe change it didn't make, through the real
 * navigator, and wiggles once a launch until a swipe has committed. On virtual clocks: a frame is
 * 16ms of both the frame clock and the test scheduler.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*VibeDomeCueTest*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
class VibeDomeCueTest {
    private val density = 2f
    private val background = Color(0xFF14141F)

    /**
     * The bar's transport alone, paused with no progress and a name that fits so a still dome asks
     * for no frames, under [cues]. Its ring's centre sits at (60, 44)dp. Composed, so shown, at
     * [nowMs] 1000; the scheduler's clock runs 1000ms behind [nowMs]. The preview seams draw a pretend drag.
     */
    private inner class Dome(
        cues: VibeDomeCues?,
        private val scheduler: TestCoroutineScheduler,
        tv: Boolean = false,
        previewDragDp: Float = 0f,
        previewTilt: Float? = null,
        // In place of [cues]: cues remembered in the scene's own root, as DjApp remembers them.
        rootCues: (@Composable () -> VibeDomeCues)? = null,
    ) {
        lateinit var motion: DomeMotion
        val scopes = ScopeCounter()
        var nexts = 0
        var previouses = 0
        var toggles = 0
        var nowMs = 1_000L
            private set

        val scene = ImageComposeScene(120 * 2, 120 * 2, Density(density), UnconfinedTestDispatcher(scheduler)) {
            ObserveScopes(scopes)
            val shown = rootCues?.invoke() ?: cues
            CompositionLocalProvider(LocalTelevisionHardware provides tv, LocalVibeDomeCues provides shown) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(background)) {
                        motion = rememberDomeMotion()
                        VibeTransportItem(
                            name = "Drift", previousName = "Dog House", nextName = "Stay Asleep",
                            progress = null, paused = true, onTogglePlayback = { toggles++ },
                            onNext = { nexts++ }, onPrevious = { previouses++ },
                            modifier = Modifier.padding(start = 24.dp, top = 8.dp).width(72.dp),
                            nameStyle = barNameStyle, previewDragDp = previewDragDp, previewDomeTilt = previewTilt,
                            domeMotion = motion,
                        )
                    }
                }
            }
        }

        private val ringCentre = Offset(60 * density, 44 * density)

        private fun step(): Image {
            nowMs += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(nowMs * 1_000_000)
        }

        /** One 16ms frame; the dome's tilt after it. */
        fun frame(): Float {
            step().close()
            return motion.tilt
        }

        /** One 16ms frame, as a PNG. */
        fun shot(): ByteArray {
            val image = step()
            try {
                return image.encodeToData()!!.bytes
            } finally {
                image.close()
            }
        }

        fun frames(count: Int): List<Float> = List(count) { frame() }

        /** The frames' tilts, peeks and recomposed scopes, one entry a frame. */
        fun watch(count: Int): List<Watched> = List(count) {
            val seen = scopes.entered
            val tilt = frame()
            Watched(nowMs - 1_000L, tilt, peek(), scopes.entered - seen)
        }

        /** The neighbour the label peeks, or null while it names the playing vibe. */
        fun peek(): String? {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            val texts = scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
                .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            return texts.firstOrNull { it == "Dog House" || it == "Stay Asleep" }
        }

        fun press() {
            scene.sendPointerEvent(PointerEventType.Press, ringCentre, timeMillis = nowMs)
            frame()
        }

        fun lift() {
            scene.sendPointerEvent(PointerEventType.Release, ringCentre, timeMillis = nowMs)
            frame()
        }

        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            frame()
        }

        /** Runs the dome's screen-reader action [label], as TalkBack does. */
        fun act(label: String) {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                .first { it.label == label }
                .action()
            frame()
        }

        /** A finger right across the dome, [dxDp] in 4dp steps a frame apart, and off. */
        fun swipeRight(dxDp: Int) {
            scene.sendPointerEvent(PointerEventType.Press, ringCentre, timeMillis = nowMs)
            frame()
            (4..dxDp step 4).forEach {
                scene.sendPointerEvent(PointerEventType.Move, ringCentre + Offset(it * density, 0f), timeMillis = nowMs)
                frame()
            }
            scene.sendPointerEvent(PointerEventType.Release, ringCentre + Offset(dxDp * density, 0f), timeMillis = nowMs)
        }

        val stillAsksForFrames: Boolean get() = scene.hasInvalidations()

        init {
            frames(2)
        }

        fun close() = scene.close()
    }

    /** A frame [ms] after the dome showed: its tilt, the label's peek, and the scopes it recomposed. */
    private data class Watched(val ms: Long, val tilt: Float, val peek: String?, val scopes: Int)

    /** Frame steps where the tilt went over an edge: +1 from past +0.5 to below −0.5 (a right roll), −1 the reverse. */
    private fun wraps(tilts: List<Float>): List<Int> = (listOf(0f) + tilts).zipWithNext().mapNotNull { (a, b) ->
        when {
            a > 0.5f && b < -0.5f -> 1
            a < -0.5f && b > 0.5f -> -1
            else -> null
        }
    }

    /**
     * One whole roll the [direction] way, as a swipe's release rolls: setting off that way, over
     * that edge once, and still at rest by the end of [tilts].
     */
    private fun assertOneRoll(direction: Int, tilts: List<Float>, what: String) {
        assertTrue(tilts.first { it != 0f } * direction > 0f, "$what set off the wrong way: $tilts")
        assertEquals(listOf(direction), wraps(tilts), "$what: expected one roll over the ${if (direction > 0) "right" else "left"} edge: $tilts")
        // A right roll lands on −0.0, which boxed equality tells from 0.
        assertEquals(0f, tilts.last(), 0f, "$what never came to rest: $tilts")
    }

    // ==================== the pure mapping ====================

    @Test
    fun onwardRollsRightBackRollsLeftAndItsOwnSwipeNotAtAll() {
        for (origin in listOf(VibeMoveOrigin.Request, VibeMoveOrigin.Advance)) {
            assertEquals(1, domeRollFor(VibeMove(VibeMoveKind.Next, origin)))
            assertEquals(1, domeRollFor(VibeMove(VibeMoveKind.Pick, origin)))
            assertEquals(-1, domeRollFor(VibeMove(VibeMoveKind.Previous, origin)))
            assertEquals(-1, domeRollFor(VibeMove(VibeMoveKind.Restart, origin)))
        }
        VibeMoveKind.entries.forEach { assertEquals(0, domeRollFor(VibeMove(it, VibeMoveOrigin.DomeSwipe))) }
    }

    // ==================== rolls through the real navigator ====================

    @Test
    fun aNavigatorNextRollsTheDomeRight() {
        val scheduler = TestCoroutineScheduler()
        val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
        val dome = Dome(rig.cues, scheduler)
        try {
            assertFalse(dome.stillAsksForFrames, "sanity: a still dome asks for frames")
            val seen = dome.scopes.entered
            rig.navigator.onSkip(SkipDirection.NEXT)
            val tilts = dome.frames(40)
            assertEquals("Stay Asleep", rig.feature.vibeFlow.value.name)
            assertOneRoll(1, tilts, "a navigator Next")
            assertFalse(dome.stillAsksForFrames, "a settled dome still asks for frames")
            assertEquals(seen, dome.scopes.entered, "the roll recomposed")
        } finally {
            dome.close()
            rig.close()
        }
    }

    /** Each way a vibe changes without the dome: it rolls once, the change's way, and settles. */
    @Test
    fun everyChangeItDidntMakeRollsOnceTheRightWay() {
        val scheduler = TestCoroutineScheduler()
        val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
        val dome = Dome(rig.cues, scheduler)
        val advancer = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        val playing = { rig.feature.vibeFlow.value.name }
        val changes: List<Triple<String, Int, () -> Unit>> = listOf(
            Triple("→, media Next, the widget's Next", 1) { rig.navigator.onSkip(SkipDirection.NEXT) },
            Triple("⏭ and the dock's step tile", 1) { rig.session.requestVibe(VibeRequest.Next) },
            Triple("←, media Previous, the widget's Previous", -1) { rig.navigator.onSkip(SkipDirection.PREVIOUS) },
            Triple("⏮ and the dock's step tile", -1) { rig.session.requestVibe(VibeRequest.Previous) },
            Triple("a pick from a VIBE list", 1) { rig.session.requestVibe(VibeRequest.Pick(rig.feature.vibeFlow.value.copy(name = "Dog House"))) },
            Triple("the song's end", 1) {
                val from = playing()
                advancer.launch { rig.navigator.advance(from, "Stay Asleep", TransitionSpec(TransitionStyle.CUT)) }
            },
            Triple("◀ late in a song, a restart", -1) {
                // Ten seconds in: past the five that make ◀ a restart.
                rig.session.updateProgress(PlaybackProgress(10_000, 200_000))
                rig.navigator.onSkip(SkipDirection.PREVIOUS)
            },
        )
        try {
            changes.forEachIndexed { i, (what, direction, change) ->
                val before = playing()
                change()
                assertEquals(i + 1, rig.moves.size, "$what announced no move")
                assertOneRoll(direction, dome.frames(40), what)
                assertFalse(dome.stillAsksForFrames, "after $what the dome still asks for frames")
                if (i < changes.lastIndex) assertNotEquals(before, playing(), "sanity: $what changed nothing")
            }
            assertEquals(VibeMoveKind.Restart, rig.moves.last().kind, "sanity: the late ◀ was not a restart")
        } finally {
            advancer.cancel()
            dome.close()
            rig.close()
        }
    }

    // The navigator takes the swipe up only once its release roll has settled, so a second roll
    // for the same change could not hide inside the first.
    @Test
    fun aDomeSwipeRollsExactlyOnce() {
        val scheduler = TestCoroutineScheduler()
        val navScheduler = TestCoroutineScheduler()
        val rig = VibeDomeRig(StandardTestDispatcher(navScheduler))
        navScheduler.runCurrent()
        val dome = Dome(rig.cues, scheduler)
        try {
            dome.swipeRight(48)
            assertOneRoll(1, dome.frames(40), "the swipe's release")
            assertEquals(0, dome.nexts, "under the cues a swipe asks through them, not onNext")
            navScheduler.runCurrent()
            assertEquals(listOf(VibeMove(VibeMoveKind.Next, VibeMoveOrigin.DomeSwipe)), rig.moves)
            assertEquals("Stay Asleep", rig.feature.vibeFlow.value.name)
            val after = dome.frames(40)
            assertTrue(after.all { it == 0f }, "the dome rolled again for its own swipe: $after")
            assertFalse(dome.stillAsksForFrames)

            // Control: a change arriving just as late from ⏭ does roll.
            rig.session.requestVibe(VibeRequest.Next)
            navScheduler.runCurrent()
            assertOneRoll(1, dome.frames(40), "a late ⏭")
        } finally {
            dome.close()
            rig.close()
        }
    }

    // In the app the swipe's move lands a frame or so into its release roll. A second roll begun
    // there would only stall the first near the edge, so the roll is held to a cue-less dome's, frame by frame.
    @Test
    fun aDomeSwipeWhoseMoveLandsMidRollRollsExactlyAsItsReleaseAlone() {
        for (landsAfter in 1..3) {
            fun swipeRoll(withNavigator: Boolean): List<Float> {
                val scheduler = TestCoroutineScheduler()
                val navScheduler = TestCoroutineScheduler()
                val rig = VibeDomeRig(StandardTestDispatcher(navScheduler))
                navScheduler.runCurrent()
                val dome = Dome(if (withNavigator) rig.cues else null, scheduler)
                try {
                    dome.swipeRight(48)
                    val tilts = dome.frames(landsAfter)
                    navScheduler.runCurrent()
                    if (withNavigator) assertEquals(listOf(VibeMove(VibeMoveKind.Next, VibeMoveOrigin.DomeSwipe)), rig.moves)
                    return tilts + dome.frames(40 - landsAfter).also { assertFalse(dome.stillAsksForFrames) }
                } finally {
                    dome.close()
                    rig.close()
                }
            }
            val alone = swipeRoll(withNavigator = false)
            assertOneRoll(1, alone, "sanity: a swipe")
            assertEquals(alone, swipeRoll(withNavigator = true), "a move landing ${landsAfter * 16}ms in changed the swipe's roll")
        }
    }

    // An AI's vibe goes to the feature at once, with no transition, as PulsarPanelActions.setVibe does.
    @Test
    fun anAiSetVibeDoesNotRoll() {
        val scheduler = TestCoroutineScheduler()
        val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
        val dome = Dome(rig.cues, scheduler)
        try {
            rig.feature.applyVibe(rig.feature.vibeFlow.value.copy(name = "AI Vibe"))
            val tilts = dome.frames(40)
            assertEquals("AI Vibe", rig.feature.vibeFlow.value.name)
            assertTrue(tilts.all { it == 0f }, "an AI's vibe rolled the dome: $tilts")
            assertEquals(emptyList<VibeMove>(), rig.moves)
            assertFalse(dome.stillAsksForFrames)
        } finally {
            dome.close()
            rig.close()
        }
    }

    // ▶▶: each roll restarts from wherever the dome is, never queued, never tipping left first.
    @Test
    fun twoQuickNextsBothRollRightAndTheDomeSettles() {
        for ((gapFrames, expectedWraps) in listOf(4 to 1, 12 to 2)) {
            val scheduler = TestCoroutineScheduler()
            val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
            val dome = Dome(rig.cues, scheduler)
            try {
                val seen = dome.scopes.entered
                rig.navigator.onSkip(SkipDirection.NEXT)
                val first = dome.frames(gapFrames)
                rig.navigator.onSkip(SkipDirection.NEXT)
                val second = dome.frames(40)
                val what = "two Nexts ${gapFrames * 16}ms apart"
                assertEquals(2, rig.moves.size, "$what: sanity")
                assertTrue(first.first { it != 0f } > 0f, "$what: the first set off left: $first")
                assertTrue(second.first() >= first.last() || second.first() > 0f, "$what: the second tipped left first: $first / $second")
                assertEquals(List(expectedWraps) { 1 }, wraps(first + second), "$what: $first / $second")
                assertEquals(0f, second.last(), 0f, "$what never settled")
                assertFalse(dome.stillAsksForFrames, "$what: still asks for frames")
                assertEquals(seen, dome.scopes.entered, "$what recomposed")
            } finally {
                dome.close()
                rig.close()
            }
        }
    }

    @Test
    fun onTelevisionHardwareNothingRolls() {
        val scheduler = TestCoroutineScheduler()
        val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
        val dome = Dome(rig.cues, scheduler, tv = true)
        try {
            rig.navigator.onSkip(SkipDirection.NEXT)
            val tilts = dome.frames(40)
            assertEquals(1, rig.moves.size, "sanity: the navigator moved")
            assertTrue(tilts.all { it == 0f }, "a TV dome rolled: $tilts")
            assertFalse(dome.stillAsksForFrames)
        } finally {
            dome.close()
            rig.close()
        }
    }

    // ==================== the "swipe me" wiggle ====================

    /** One launch of the app: fresh cues over [preferences] with no moves, recording the swipes they ask for. */
    private class Launch(val preferences: MemoryPreferences, scheduler: TestCoroutineScheduler) {
        val swipes = mutableListOf<Boolean>()
        private val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        val cues = VibeDomeCues(emptyFlow(), { swipes += it }, preferences, scope, DomeWiggleLaunch())

        fun close() = scope.cancel()
    }

    /** A wiggling dome's frames, the dome and launch closed after. */
    private fun wiggle(frames: Int = 200, preferences: MemoryPreferences = MemoryPreferences()): List<Watched> {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(preferences, scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            return dome.watch(frames).also { assertFalse(dome.stillAsksForFrames, "a settled wiggle still asks for frames") }
        } finally {
            dome.close()
            launch.close()
        }
    }

    private fun List<Watched>.moving() = filter { it.tilt != 0f }

    @Test
    fun aSecondAndAHalfInTheDomeTipsLeftThenRightPeekingEachNeighbourAndSettles() {
        val scheduler = TestCoroutineScheduler()
        val preferences = MemoryPreferences()
        val launch = Launch(preferences, scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            val waiting = dome.watch(85)
            assertTrue(waiting.all { it.tilt == 0f && it.peek == null }, "the dome moved before its beat: ${waiting.moving()}")
            assertFalse(dome.stillAsksForFrames, "waiting for the wiggle asks for frames")
            val frames = waiting + dome.watch(115)
            val moving = frames.moving()
            val start = moving.first().ms
            val length = moving.last().ms - start
            assertTrue(start in DomeWiggleDelayMillis..DomeWiggleDelayMillis + 50, "the wiggle began ${start}ms after the dome showed")
            assertTrue(length in 1_000L..1_300L, "the wiggle lasted ${length}ms")
            assertTrue(moving.first().tilt < 0f, "it tipped right first")

            // As far each way as a 20dp drag, left first, peeking the neighbour that way; never near the commit.
            val left = frames.minBy { it.tilt }
            val right = frames.maxBy { it.tilt }
            assertEquals(domeTiltForDrag(-DomeWiggleDragDp), left.tilt, 1e-6f)
            assertEquals(domeTiltForDrag(DomeWiggleDragDp), right.tilt, 1e-6f)
            assertTrue(left.ms < right.ms, "right before left")
            assertEquals("Dog House", left.peek, "the left tip never peeked the previous vibe")
            assertEquals("Stay Asleep", right.peek, "the right tip never peeked the next vibe")
            assertEquals(listOf("Dog House", "Stay Asleep"), frames.mapNotNull { it.peek }.distinct())
            assertEquals(SwipeDecision.None, swipeDecision(DomeWiggleDragDp, 0f), "a wiggle's drag would commit")

            assertEquals(0f, frames.last().tilt, 0f, "it never settled")
            assertEquals(null, frames.last().peek, "the label still peeks")
            assertFalse(dome.stillAsksForFrames, "a settled wiggle still asks for frames")
            assertEquals(emptyList<Boolean>(), launch.swipes)
            assertEquals(0, dome.nexts + dome.previouses + dome.toggles)
            assertFalse(preferences.prefs.domeSwiped, "a wiggle is not a swipe")
        } finally {
            dome.close()
            launch.close()
        }
    }

    // The drag and the tilt are read in draw and placement: only the peek's arrival and departure
    // each way recompose, as on a real drag.
    @Test
    fun theWiggleRecomposesOnlyAsThePeekComesAndGoes() {
        val frames = wiggle()
        val recomposed = frames.filter { it.scopes > 0 }
        assertEquals(4, recomposed.size, "frames that recomposed: $recomposed")
        recomposed.zipWithNext().forEach { (a, b) -> assertNotEquals(a.peek, b.peek, "a recomposition changed no peek: $recomposed") }
    }

    /** At each hold the wiggle draws exactly what a real 20dp drag draws, peek label and all. */
    @Test
    fun atEachHoldTheWiggleDrawsExactlyWhatARealDragThatFarDraws() {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(MemoryPreferences(), scheduler)
        val dome = Dome(launch.cues, scheduler)
        val held = mutableMapOf<Float, ByteArray>()
        try {
            repeat(200) {
                val shot = dome.shot()
                val tilt = dome.motion.tilt
                if (kotlin.math.abs(tilt) == 0.5f) held.getOrPut(tilt) { shot }
            }
        } finally {
            dome.close()
            launch.close()
        }
        assertEquals(setOf(-0.5f, 0.5f), held.keys, "the wiggle never held at either tip")
        for ((drag, tilt) in listOf(-DomeWiggleDragDp to -0.5f, DomeWiggleDragDp to 0.5f)) {
            val real = Dome(null, TestCoroutineScheduler(), previewDragDp = drag, previewTilt = tilt)
            try {
                real.frames(3)
                assertTrue(real.shot().contentEquals(held.getValue(tilt)), "the wiggle's ${drag}dp hold draws differently from a real drag")
            } finally {
                real.close()
            }
        }
    }

    @Test
    fun aCommittedSwipeIsRememberedAndTheNextLaunchDoesNotWiggle() {
        val preferences = MemoryPreferences()
        val scheduler = TestCoroutineScheduler()
        val first = Launch(preferences, scheduler)
        val dome = Dome(first.cues, scheduler)
        try {
            assertTrue(dome.watch(200).moving().isNotEmpty(), "sanity: the first launch never wiggled")
            dome.swipeRight(48)
            dome.frames(40)
            assertEquals(listOf(true), first.swipes, "the swipe asked for no vibe")
            assertTrue(preferences.prefs.domeSwiped, "the swipe was not remembered")
        } finally {
            dome.close()
            first.close()
        }
        val frames = wiggle(frames = 250, preferences = preferences)
        assertEquals(emptyList<Watched>(), frames.moving(), "the next launch wiggled")
    }

    // The same launch: a swipe before the beat takes the wiggle away at once.
    @Test
    fun aSwipeBeforeTheBeatMeansNoWiggle() {
        val scheduler = TestCoroutineScheduler()
        val preferences = MemoryPreferences()
        val launch = Launch(preferences, scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            dome.frames(10)
            dome.swipeRight(48)
            dome.frames(40)
            val after = dome.watch(150)
            assertEquals(emptyList<Watched>(), after.moving(), "it wiggled after a swipe")
            assertTrue(preferences.prefs.domeSwiped)
        } finally {
            dome.close()
            launch.close()
        }
    }

    // TalkBack's stand-ins for the swipe teach it as well as a swipe does: no wiggle this launch or the next.
    @Test
    fun talkBacksNextAndPreviousCountAsTheSwipeLearned() {
        for (label in listOf("Next vibe", "Previous vibe")) {
            val preferences = MemoryPreferences()
            val scheduler = TestCoroutineScheduler()
            val launch = Launch(preferences, scheduler)
            val dome = Dome(launch.cues, scheduler)
            try {
                dome.frames(10)
                dome.act(label)
                assertEquals(1, dome.nexts + dome.previouses, "sanity: \"$label\" moved nothing")
                assertTrue(preferences.prefs.domeSwiped, "\"$label\" was not taken for the swipe learned")
                assertEquals(emptyList<Watched>(), dome.watch(150).moving(), "the dome wiggled after \"$label\"")
            } finally {
                dome.close()
                launch.close()
            }
            assertEquals(emptyList<Watched>(), wiggle(frames = 250, preferences = preferences).moving(), "the launch after \"$label\" wiggled")
        }
    }

    // Taps, a short drag and the buttons are not a swipe: the next launch still wiggles.
    @Test
    fun onlyACommittedSwipeCounts() {
        val preferences = MemoryPreferences()
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(preferences, scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            dome.watch(200)
            dome.press()
            dome.lift()
            dome.swipeRight(20)
            dome.frames(40)
            assertEquals(1, dome.toggles, "sanity: the tap never toggled playback")
            assertEquals(emptyList<Boolean>(), launch.swipes, "a 20dp drag committed")
            assertFalse(preferences.prefs.domeSwiped, "a tap or a short drag was taken for a swipe")
        } finally {
            dome.close()
            launch.close()
        }
        assertTrue(wiggle(preferences = preferences).moving().isNotEmpty(), "the next launch never wiggled")
    }

    @Test
    fun aTouchBeforeTheBeatSkipsIt() {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(MemoryPreferences(), scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            dome.frames(30)
            dome.press()
            dome.lift()
            val frames = dome.watch(200)
            assertEquals(1, dome.toggles, "sanity: the tap never landed")
            assertEquals(emptyList<Watched>(), frames.moving(), "a touched dome still wiggled")
            assertFalse(dome.stillAsksForFrames)
        } finally {
            dome.close()
            launch.close()
        }
    }

    @Test
    fun aKeyOnTheDomeBeforeTheBeatSkipsIt() {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(MemoryPreferences(), scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            dome.frames(10)
            // Tab moves focus onto the dome; the space bar then clicks it.
            dome.key(Key.Tab)
            dome.key(Key.Spacebar)
            assertEquals(1, dome.toggles, "sanity: the dome never had focus")
            val frames = dome.watch(200)
            assertEquals(emptyList<Watched>(), frames.moving(), "a dome that heard a key still wiggled")
        } finally {
            dome.close()
            launch.close()
        }
    }

    @Test
    fun aTouchMidWiggleStopsItWhereItIs() {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(MemoryPreferences(), scheduler)
        val dome = Dome(launch.cues, scheduler)
        try {
            // On to the left tip, the previous vibe peeking.
            val tip = generateSequence { dome.watch(1).single() }.take(150).firstOrNull { it.tilt == -0.5f }
            assertNotNull(tip, "never reached the left tip")
            assertEquals("Dog House", tip.peek)
            dome.press()
            assertEquals(null, dome.peek(), "the peek outlived the touch")
            val after = dome.watch(60)
            assertTrue(after.all { it.tilt < 0.25f }, "it wiggled on to the right: ${after.moving()}")
            assertTrue(after.all { it.peek == null }, "it peeked again: $after")
            assertEquals(0f, after.last().tilt, 0f, "it never sprang home")
            dome.lift()
            assertEquals(emptyList<Watched>(), dome.watch(100).moving(), "it moved again after the finger left")
            assertFalse(dome.stillAsksForFrames)
        } finally {
            dome.close()
            launch.close()
        }
    }

    // A rotation rebuilds the chrome: the wiggle already given is not given again, one still due is.
    @Test
    fun theWiggleRunsOnceALaunchWhicheverDomeShowsIt() {
        val scheduler = TestCoroutineScheduler()
        val launch = Launch(MemoryPreferences(), scheduler)
        try {
            val early = Dome(launch.cues, scheduler)
            early.frames(60)
            early.close()
            val next = Dome(launch.cues, scheduler)
            val frames = next.watch(200)
            next.close()
            val start = frames.moving().firstOrNull()?.ms
            assertTrue(start != null && start in DomeWiggleDelayMillis..DomeWiggleDelayMillis + 50, "a dome gone before its beat left no wiggle, or a late one: $start")
            val later = Dome(launch.cues, scheduler)
            val again = later.watch(200)
            later.close()
            assertEquals(emptyList<Watched>(), again.moving(), "a second dome in the same launch wiggled again")
        } finally {
            launch.close()
        }
    }

    // An Android activity recreated for a rotation or a fold rebuilds DjApp's root, and its cues with
    // it: the wiggle this process already gave is not given again.
    @Test
    fun aRecreatedRootDoesNotWiggleAgain() {
        val preferences = MemoryPreferences()
        val feature = RigPulsarFeature()
        val process = DomeWiggleLaunch()
        fun activity(wiggle: DomeWiggleLaunch): List<Watched> {
            val dome = Dome(null, TestCoroutineScheduler(), rootCues = { rememberVibeDomeCues(feature, preferences, wiggle) })
            try {
                return dome.watch(200)
            } finally {
                dome.close()
            }
        }
        assertTrue(activity(process).moving().isNotEmpty(), "sanity: the first activity never wiggled")
        assertEquals(emptyList<Watched>(), activity(process).moving(), "the recreated activity wiggled again")
        // Control: a new process, the swipe never having committed, wiggles again.
        assertTrue(activity(DomeWiggleLaunch()).moving().isNotEmpty(), "a new process never wiggled")
    }

    @Test
    fun onTelevisionHardwareNothingWiggles() {
        val scheduler = TestCoroutineScheduler()
        val preferences = MemoryPreferences()
        val launch = Launch(preferences, scheduler)
        val dome = Dome(launch.cues, scheduler, tv = true)
        try {
            val frames = dome.watch(250)
            assertTrue(frames.all { it.tilt == 0f && it.peek == null }, "a TV dome wiggled")
            assertFalse(dome.stillAsksForFrames)
            assertEquals(0, preferences.loads, "a TV dome asked the store about a wiggle it never gives")
        } finally {
            dome.close()
            launch.close()
        }
    }

    // ==================== every dome: the phone bar and the rail ====================

    /**
     * The nav scaffold in [layout] under cues fed from [moves] and [preferences], paused with no
     * progress and the rig's short name: still until something moves it.
     */
    private inner class Nav(
        layout: DjLayout,
        widthDp: Int,
        heightDp: Int,
        moves: MutableSharedFlow<VibeMove> = MutableSharedFlow(),
        preferences: MemoryPreferences = MemoryPreferences(domeSwiped = true),
    ) {
        private val scheduler = TestCoroutineScheduler()
        private var nowMs = 1_000L
        private val feature = RigPulsarFeature()
        private val cues = VibeDomeCues(moves, {}, preferences, CoroutineScope(UnconfinedTestDispatcher(scheduler)), DomeWiggleLaunch())
        val scopes = ScopeCounter()
        val scene = ImageComposeScene(widthDp * 2, heightDp * 2, Density(density), UnconfinedTestDispatcher(scheduler)) {
            ObserveScopes(scopes)
            CompositionLocalProvider(LocalVibeDomeCues provides cues) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab }, onItemClick = {}, layout = layout, pulsarFeature = feature,
                        timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize().background(background),
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }

        fun frame(): ByteArray {
            nowMs += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(nowMs * 1_000_000).encodeToData()!!.bytes
        }

        /** Whether the transport's label peeks a neighbour. */
        fun peeks(): Boolean {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
                .any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Dog House" || it.text == "Stay Asleep" } }
        }

        init {
            repeat(3) { frame() }
        }

        fun close() = scene.close()
    }

    private val chromes = listOf(DjLayout.Portrait to (360 to 780), DjLayout.Landscape to (800 to 360))

    @Test
    fun thePhoneBarAndTheRailRollToo() {
        for ((layout, size) in chromes) {
            val moves = MutableSharedFlow<VibeMove>(extraBufferCapacity = 4)
            val nav = Nav(layout, size.first, size.second, moves)
            try {
                val rest = nav.frame()
                assertFalse(nav.scene.hasInvalidations(), "sanity: the $layout bar asks for frames at rest")
                val seen = nav.scopes.entered
                moves.tryEmit(VibeMove(VibeMoveKind.Next, VibeMoveOrigin.Advance))
                val rolling = List(4) { nav.frame() }
                assertTrue(rolling.any { !it.contentEquals(rest) }, "the $layout dome never moved")
                repeat(30) { nav.frame() }
                assertTrue(nav.frame().contentEquals(rest), "the $layout dome never came back to rest")
                assertFalse(nav.scene.hasInvalidations(), "the $layout bar still asks for frames")
                assertEquals(seen, nav.scopes.entered, "the roll recomposed the $layout chrome")
            } finally {
                nav.close()
            }
        }
    }

    // ==================== the dock's centre dome ====================

    /**
     * The dock's bottom bar under [cues], its centre dome still on the rig's short name with no
     * progress. It arrives a frame after the scene starts, as the dock does, so the dome takes the
     * launch focus. Pixels are compared on the ring alone: the name under it changes with the vibe.
     */
    private inner class Dock(cues: VibeDomeCues?, private val scheduler: TestCoroutineScheduler, feature: PulsarFeature = RigPulsarFeature()) {
        private var nowMs = 1_000L
        private val shown = mutableStateOf(false)
        val scene = ImageComposeScene(1280, 200, Density(1f), StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalVibeDomeCues provides cues) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(background)) {
                        if (shown.value) DjTvBottomBar(
                            panels = bottomBarPanels(largeScreenPanels()), isDocked = { false }, onToggle = {},
                            timerFeature = TimerViewModel.previewFeature(), pulsarFeature = feature, onTogglePlayback = {},
                        )
                    }
                }
            }
        }

        fun frame(): ByteArray {
            nowMs += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(nowMs * 1_000_000).encodeToData()!!.bytes
        }

        private fun dome(): SemanticsNode {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            return scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
                .first { n -> n.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Next vibe" } }
        }

        val domeFocused: Boolean get() = dome().config.getOrNull(SemanticsProperties.Focused) == true

        /** The neighbour the dome's own label peeks; the step tiles name them too, in nodes of their own. */
        fun peek(): String? = dome().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            .firstOrNull { it == "Dog House" || it == "Stay Asleep" }

        /** The ring's box, under the dome node's 4dp padding. */
        val ring: IntRect by lazy {
            val node = dome()
            val centre = node.positionInRoot.x + node.size.width / 2f
            val top = node.positionInRoot.y + TransportPadding.value
            IntRect((centre - BarRingSize.value / 2).toInt(), top.toInt(), (centre + BarRingSize.value / 2).toInt(), (top + BarRingSize.value).toInt())
        }

        /** This frame's ring, as ARGB pixels. */
        fun ringPixels(): IntArray {
            val px = Image.makeFromEncoded(frame()).toComposeImageBitmap().toPixelMap()
            return IntArray(ring.width * ring.height) { px[ring.left + it % ring.width, ring.top + it / ring.width].toArgb() }
        }

        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            frame()
        }

        init {
            frame()
            shown.value = true
            repeat(3) { frame() }
        }

        fun close() = scene.close()
    }

    @Test
    fun theDockDomeRollsWithANavigatorNext() {
        for (withCues in listOf(true, false)) {
            val scheduler = TestCoroutineScheduler()
            val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler))
            val dock = Dock(if (withCues) rig.cues else null, scheduler, rig.feature)
            try {
                val rest = dock.ringPixels()
                rig.navigator.onSkip(SkipDirection.NEXT)
                assertEquals("Stay Asleep", rig.feature.vibeFlow.value.name, "sanity: the navigator never moved")
                val rolled = List(8) { dock.ringPixels() }.count { !it.contentEquals(rest) }
                repeat(30) { dock.frame() }
                val after = dock.ringPixels()
                if (withCues) {
                    assertTrue(rolled > 3, "the dock's dome never rolled with a navigator Next: $rolled frames moved")
                    assertTrue(after.contentEquals(rest), "the dock's dome never came back to rest")
                } else {
                    // Control: the ring changes with nothing else, so what moved above was the roll.
                    assertEquals(0, rolled, "sanity: a dome without cues moved on a navigator Next")
                }
            } finally {
                dock.close()
                rig.close()
            }
        }
    }

    // The dock's dome holds the launch focus, so any key, an arrow on its way to the window
    // included, passes it and ends the wiggle, to come or under way.
    @Test
    fun theDockDomeWigglesAndAnyKeyCancelsIt() {
        fun dock(block: (Dock) -> Unit) {
            val scheduler = TestCoroutineScheduler()
            val launch = Launch(MemoryPreferences(), scheduler)
            val dock = Dock(launch.cues, scheduler)
            try {
                assertTrue(dock.domeFocused, "sanity: the dock's dome did not take the launch focus")
                block(dock)
            } finally {
                dock.close()
                launch.close()
            }
        }
        dock { dock ->
            val rest = dock.ringPixels()
            val peeks = mutableSetOf<String>()
            var moved = 0
            repeat(200) {
                if (!dock.ringPixels().contentEquals(rest)) moved++
                dock.peek()?.let(peeks::add)
            }
            assertTrue(moved > 30, "the dock's dome barely wiggled: $moved frames")
            assertEquals(setOf("Dog House", "Stay Asleep"), peeks, "the dock's dome did not peek each neighbour")
            assertTrue(dock.ringPixels().contentEquals(rest), "the dock's wiggle never settled")
        }
        dock { dock ->
            repeat(10) { dock.frame() }
            dock.key(Key.DirectionRight)
            val rest = dock.ringPixels()
            val moved = (1..200).count { _ -> !dock.ringPixels().contentEquals(rest) }
            assertEquals(0, moved, "a key before the beat left the dock's wiggle to come")
        }
        dock { dock ->
            val rest = dock.ringPixels()
            assertNotNull((1..150).firstOrNull { dock.frame(); dock.peek() == "Dog House" }, "the dock's dome never reached its left tip")
            dock.key(Key.DirectionRight)
            val after = List(60) { dock.frame(); dock.peek() }
            assertEquals(List(60) { null }, after, "the dock's dome kept peeking after a key")
            assertTrue(dock.ringPixels().contentEquals(rest), "the dock's dome never sprang home after a key")
        }
    }

    @Test
    fun thePhoneBarAndTheRailWiggleToo() {
        for ((layout, size) in chromes) {
            val nav = Nav(layout, size.first, size.second, preferences = MemoryPreferences())
            try {
                val rest = nav.frame()
                var peeked = 0
                var moved = 0
                var recomposed = 0
                repeat(200) {
                    val seen = nav.scopes.entered
                    if (!nav.frame().contentEquals(rest)) moved++
                    if (nav.peeks()) peeked++
                    if (nav.scopes.entered > seen) recomposed++
                }
                assertTrue(moved > 30, "the $layout dome barely wiggled: $moved frames")
                assertTrue(peeked > 10, "the $layout label barely peeked: $peeked frames")
                assertEquals(4, recomposed, "the $layout chrome recomposed beyond the peek's comings and goings")
                assertTrue(nav.frame().contentEquals(rest), "the $layout dome never came back to rest")
                assertFalse(nav.scene.hasInvalidations(), "the $layout bar still asks for frames")
            } finally {
                nav.close()
            }
        }
    }
}

package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.FixturesDispatchers
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.makeVibeNavigator
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VibeNavigatorTest {

    /** Holds each transition open for [preApplyMs] before the swap, so a second request can land mid-flight. */
    private class DelayingRunner(private val preApplyMs: Long = 0L) : PulsarTransitionRunner {
        val specs = mutableListOf<TransitionSpec>()
        override val activeStyle: StateFlow<TransitionStyle?> = MutableStateFlow(null)
        override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
            specs += spec
            if (preApplyMs > 0) kotlinx.coroutines.delay(preApplyMs)
            applyNext()
        }
    }

    /** Fails its first transition before the swap, then behaves like a CUT. */
    private class ThrowOnceRunner : PulsarTransitionRunner {
        var calls = 0
        override val activeStyle: StateFlow<TransitionStyle?> = MutableStateFlow(null)
        override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
            if (calls++ == 0) error("transition blew up")
            applyNext()
        }
    }

    private val abc = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"))

    private fun TestScope.dispatcher() = UnconfinedTestDispatcher(testScheduler)
    private fun TestScope.session() =
        PulsarSession(SongEndingStubSynthEngine(), makeAppCoroutineScope(dispatcher()), FixturesDispatchers(dispatcher()))

    /** Every vibe the feature actually applied, in order, so a test can prove a cancelled target never landed. */
    private fun FakePulsarFeature.recordApplies(): List<String> {
        val applied = mutableListOf<String>()
        onVibeApplied = { applied += vibeFlow.value.name }
        return applied
    }

    @Test
    fun nextUsesTheConfiguredDefaultSpec() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val runner = DelayingRunner()
        val nav = makeVibeNavigator(feature, runner, StubTransitionPreferences(TransitionSpec(TransitionStyle.CROSSFADE)), dispatcher())
        nav.onSkip(SkipDirection.NEXT)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(TransitionStyle.CROSSFADE, runner.specs.single().style)
    }

    @Test
    fun previousWrapsWhenProgressIsUnknown() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher())
        nav.onSkip(SkipDirection.PREVIOUS)
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)
    }

    @Test
    fun aRepeatedTargetIsNotDropped() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val runner = DelayingRunner()
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        nav.request(VibeRequest.Pick(abc[0]))
        advanceUntilIdle()
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(3, runner.specs.size)
    }

    @Test
    fun twoNextsInOneTransitionLandTwoOn() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val runner = DelayingRunner(preApplyMs = 1_000)
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        nav.request(VibeRequest.Next)
        advanceTimeBy(100)
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(listOf("C"), applied, "the cancelled step to B must never land")
    }

    @Test
    fun previousThenNextInOneTransitionComesBack() = runTest {
        val feature = FakePulsarFeature(abc, abc[1])
        val applied = feature.recordApplies()
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher())
        nav.request(VibeRequest.Previous)
        advanceTimeBy(100)
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(listOf("B"), applied, "the cancelled step to A must never land")
    }

    @Test
    fun previousPastTheThresholdMidTransitionStepsBack() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val session = session()
        session.updateProgress(PlaybackProgress(RestartAfterMs + 1_000, 200_000))
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher(), session = session)
        nav.request(VibeRequest.Pick(abc[2]))
        advanceTimeBy(100)
        nav.request(VibeRequest.Previous)
        advanceUntilIdle()
        // A restart would replay A; mid-flight, ◀ steps back from the pending C instead.
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(listOf("B"), applied)
    }

    @Test
    fun previousPastTheThresholdRestartsTheSong() = runTest {
        val feature = FakePulsarFeature(abc, abc[1])
        var applies = 0
        feature.onVibeApplied = { applies++ }
        val session = session()
        session.updateProgress(PlaybackProgress(RestartAfterMs + 1_000, 200_000))
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        nav.request(VibeRequest.Previous)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(1, applies)
    }

    @Test
    fun previousRestartsAnAiVibeNotInTheCatalog() = runTest {
        val ai = mkMinimalVibe("AI Vibe")
        val feature = FakePulsarFeature(abc, ai)
        val session = session()
        session.updateProgress(PlaybackProgress(RestartAfterMs, 200_000))
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        nav.request(VibeRequest.Previous)
        advanceUntilIdle()
        assertEquals("AI Vibe", feature.vibeFlow.value.name)
    }

    @Test
    fun aSessionPickRunsThroughTheRunner() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val runner = DelayingRunner()
        val session = session()
        makeVibeNavigator(feature, runner, dispatcher = dispatcher(), session = session)
        session.requestVibe(VibeRequest.Pick(abc[2]))
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(1, runner.specs.size)
    }

    @Test
    fun aUserRequestCancelsAnAdvanceAndReleasesTheAdvancer() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val runner = DelayingRunner(preApplyMs = 1_000)
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        var released = false
        backgroundScope.launch(dispatcher()) {
            nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE))
            released = true
        }
        advanceTimeBy(100)
        assertTrue(nav.isBusy)
        nav.request(VibeRequest.Pick(abc[2]))
        advanceUntilIdle()
        assertTrue(released, "a cancelled advance must still return to its caller")
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(listOf("C"), applied, "the cancelled advance to B must never land")
    }

    // While A→B fades out the chrome still reads "Up next: B", so ▶ must land there, not on C.
    @Test
    fun nextDuringAnAdvanceLandsOnTheNamedNext() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher())
        backgroundScope.launch(dispatcher()) { nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE)) }
        advanceTimeBy(100)
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(listOf("B"), applied)
    }

    @Test
    fun twoNextsDuringAnAdvanceLandTwoOn() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher())
        backgroundScope.launch(dispatcher()) { nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE)) }
        advanceTimeBy(100)
        nav.request(VibeRequest.Next)
        advanceTimeBy(100)
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(listOf("C"), applied)
    }

    // Unchanged by the ▶ fix: ◀ steps back from the advance's target, which replays the playing
    // vibe, the restart the chrome names that late in a song.
    @Test
    fun previousDuringAnAdvanceReplaysThePlayingVibe() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val session = session()
        session.updateProgress(PlaybackProgress(RestartAfterMs + 1_000, 200_000))
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher(), session = session)
        backgroundScope.launch(dispatcher()) { nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE)) }
        advanceTimeBy(100)
        nav.request(VibeRequest.Previous)
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name)
        assertEquals(listOf("A"), applied)
    }

    @Test
    fun anAdvanceArrivingDuringAUserRequestIsDropped() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val applied = feature.recordApplies()
        val runner = DelayingRunner(preApplyMs = 1_000)
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        nav.request(VibeRequest.Pick(abc[2]))
        advanceTimeBy(100)
        var released = false
        backgroundScope.launch(dispatcher()) {
            nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE))
            released = true
        }
        advanceUntilIdle()
        assertTrue(released, "a dropped advance must still return to its caller")
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(listOf("C"), applied, "the user's pick wins; the advance never lands")
        assertEquals(1, runner.specs.size)
    }

    @Test
    fun anAdvanceFromASongThatAlreadyLeftIsDropped() = runTest {
        val feature = FakePulsarFeature(abc, abc[2])
        val applied = feature.recordApplies()
        val runner = DelayingRunner()
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        var released = false
        backgroundScope.launch(dispatcher()) {
            nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE))
            released = true
        }
        advanceUntilIdle()
        assertTrue(released)
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(emptyList<String>(), applied)
        assertEquals(0, runner.specs.size)
    }

    // Two quick presses under RANDOM: the first rolls FADE and is cancelled mid fade-out, the second rolls CUT.
    @Test
    fun aCutInterruptingAFadeEndsAtUnity() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val engine = SongEndingStubSynthEngine()
        val rolls = ArrayDeque(listOf(TransitionStyle.FADE, TransitionStyle.CUT))
        val runner = PulsarTransitionRunnerImpl(engine, random = { rolls.removeFirst() })
        val prefs = StubTransitionPreferences(TransitionSpec(TransitionStyle.RANDOM))
        val nav = makeVibeNavigator(feature, runner, prefs, dispatcher())
        nav.request(VibeRequest.Next)
        advanceTimeBy(50)
        assertEquals(0f, engine.getMasterVolume(), "the fade-out should be under way")
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)
        assertEquals(1f, engine.getMasterVolume(), "left silent after a CUT interrupted a fade")
    }

    @Test
    fun aTransitionThatThrowsMidFadeEndsAtUnity() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        feature.onVibeApplied = { error("apply blew up") }
        val engine = SongEndingStubSynthEngine()
        val runner = PulsarTransitionRunnerImpl(engine)
        val nav = makeVibeNavigator(feature, runner, StubTransitionPreferences(TransitionSpec(TransitionStyle.FADE)), dispatcher())
        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals(1f, engine.getMasterVolume(), "a failed swap left the fader down")
    }

    // ==================== the moves it announces ====================

    /** Every move [session] announces, in order. */
    private fun TestScope.movesOf(session: PulsarSession): List<VibeMove> {
        val moves = mutableListOf<VibeMove>()
        backgroundScope.launch(dispatcher()) { session.vibeMoves.collect { moves += it } }
        return moves
    }

    private fun request(kind: VibeMoveKind) = VibeMove(kind, VibeMoveOrigin.Request)

    @Test
    fun aMoveIsAnnouncedAsItsTransitionStartsNotAsItEnds() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher(), session = session)
        nav.request(VibeRequest.Next)
        advanceTimeBy(100)
        assertEquals(listOf(request(VibeMoveKind.Next)), moves)
        assertEquals("A", feature.vibeFlow.value.name, "sanity: the swap is still a second away")
        advanceUntilIdle()
        assertEquals(1, moves.size, "the swap announced the move a second time")
    }

    // → and ←, media keys and the widget all reach the navigator as a SkipHandler.
    @Test
    fun keysMediaKeysAndTheWidgetAnnounceStepsOnAndBack() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        nav.onSkip(SkipDirection.NEXT)
        advanceUntilIdle()
        nav.onSkip(SkipDirection.PREVIOUS)
        advanceUntilIdle()
        assertEquals(listOf(request(VibeMoveKind.Next), request(VibeMoveKind.Previous)), moves)
    }

    // ⏭ and ⏮, the dock's step tiles and the dropdowns ask through the session, as PulsarPanelActions does.
    @Test
    fun buttonsTilesAndListsAnnounceThroughTheSession() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        session.requestVibe(VibeRequest.Next)
        advanceUntilIdle()
        session.requestVibe(VibeRequest.Previous)
        advanceUntilIdle()
        session.requestVibe(VibeRequest.Pick(abc[2]))
        advanceUntilIdle()
        assertEquals(listOf(request(VibeMoveKind.Next), request(VibeMoveKind.Previous), request(VibeMoveKind.Pick)), moves)
    }

    @Test
    fun aLatePreviousAnnouncesARestart() = runTest {
        val feature = FakePulsarFeature(abc, abc[1])
        val session = session()
        session.updateProgress(PlaybackProgress(RestartAfterMs + 1_000, 200_000))
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        nav.onSkip(SkipDirection.PREVIOUS)
        advanceUntilIdle()
        assertEquals(listOf(request(VibeMoveKind.Restart)), moves)
    }

    @Test
    fun anAdvanceAnnouncesAStepOnOfItsOwn() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        backgroundScope.launch(dispatcher()) { nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE)) }
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(listOf(VibeMove(VibeMoveKind.Next, VibeMoveOrigin.Advance)), moves)
    }

    @Test
    fun aDroppedAdvanceAnnouncesNothing() = runTest {
        val feature = FakePulsarFeature(abc, abc[2])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        backgroundScope.launch(dispatcher()) { nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE)) }
        advanceUntilIdle()
        assertEquals(emptyList<VibeMove>(), moves)
    }

    // The dome rolled as the finger let go: its moves say they are its own, whichever way they went.
    @Test
    fun aDomeSwipeIsAnnouncedAsTheDomes() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        session.requestVibe(VibeRequest.DomeSwipe(next = true))
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name, "a swipe right steps on, as Next does")
        session.requestVibe(VibeRequest.DomeSwipe(next = false))
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name, "a swipe left steps back, as Previous does")
        session.updateProgress(PlaybackProgress(RestartAfterMs + 1_000, 200_000))
        session.requestVibe(VibeRequest.DomeSwipe(next = false))
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name, "a late swipe left restarts, as Previous does")
        val dome = VibeMoveOrigin.DomeSwipe
        assertEquals(
            listOf(VibeMove(VibeMoveKind.Next, dome), VibeMove(VibeMoveKind.Previous, dome), VibeMove(VibeMoveKind.Restart, dome)),
            moves,
        )
    }

    // An AI's vibe goes straight to the feature, with no transition: nothing moves.
    @Test
    fun aVibeAppliedStraightToTheFeatureAnnouncesNothing() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        feature.applyVibe(mkMinimalVibe("AI Vibe"))
        advanceUntilIdle()
        assertEquals(emptyList<VibeMove>(), moves)
    }

    @Test
    fun twoQuickNextsAnnounceTwoMoves() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(preApplyMs = 1_000), dispatcher = dispatcher(), session = session)
        nav.onSkip(SkipDirection.NEXT)
        advanceTimeBy(100)
        nav.onSkip(SkipDirection.NEXT)
        advanceUntilIdle()
        assertEquals(listOf(request(VibeMoveKind.Next), request(VibeMoveKind.Next)), moves)
    }

    @Test
    fun aStepWithNowhereToGoAnnouncesNothing() = runTest {
        val feature = FakePulsarFeature(emptyList(), abc[0])
        val session = session()
        val moves = movesOf(session)
        val nav = makeVibeNavigator(feature, DelayingRunner(), dispatcher = dispatcher(), session = session)
        nav.onSkip(SkipDirection.NEXT)
        advanceUntilIdle()
        assertEquals(emptyList<VibeMove>(), moves)
    }

    @Test
    fun aThrowingTransitionDoesNotStopNavigation() = runTest {
        val feature = FakePulsarFeature(abc, abc[0])
        val runner = ThrowOnceRunner()
        val nav = makeVibeNavigator(feature, runner, dispatcher = dispatcher())
        var released = false
        backgroundScope.launch(dispatcher()) {
            nav.advance("A", "B", TransitionSpec(TransitionStyle.FADE))
            released = true
        }
        advanceUntilIdle()
        assertTrue(released, "an advance whose transition throws must still return")
        assertEquals("A", feature.vibeFlow.value.name)

        nav.request(VibeRequest.Next)
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name, "the next request still runs after a throw")
        assertEquals(2, runner.calls)
    }
}

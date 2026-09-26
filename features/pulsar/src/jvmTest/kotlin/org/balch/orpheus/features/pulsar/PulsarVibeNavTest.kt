package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.playback.VibeRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Vibe pickers must ask [PulsarSession]'s navigator rather than applying directly, and
 * [PulsarViewModel.vibeNavFlow] must track the session's progress. See `VibeNavigator`
 * (Task 3) for the consumer side of `vibeRequests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarVibeNavTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.makeViewModelAndSession(): Pair<PulsarViewModel, PulsarSession> {
        val controller = SynthController().apply {
            setDelegates(
                setter = { _, _ -> true },
                getter = { null },
            )
        }
        val tempo = GlobalTempo(NavTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        val dispatchers = NavTestDispatchers(testDispatcher)
        val appScope = makeAppCoroutineScope(testDispatcher)
        val session = PulsarSession(engine, appScope, dispatchers)
        val vm = PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = session,
            globalTempo = tempo,
            appPreferencesRepository = NavTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(NavTestVibeProvider(mkMinimalVibe("Nav"))),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
        advanceUntilIdle()
        return vm to session
    }

    @Test
    fun pickVibeAsksTheSessionInsteadOfApplying() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        val before = vm.vibeFlow.value
        val other = mkMinimalVibe("Other")
        vm.actions.pickVibe(other)
        vm.actions.nextVibe()
        vm.actions.previousVibe()
        assertEquals(listOf(VibeRequest.Pick(other), VibeRequest.Next, VibeRequest.Previous), seen)
        assertEquals(before, vm.vibeFlow.value, "a pick waits for the navigator; it must not apply directly")
    }

    // The dome's swipe asks as the dome, so the move it causes is recognisably its own.
    @Test
    fun aDomeSwipeAsksTheSessionAsTheDome() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        vm.actions.swipeVibe(true)
        vm.actions.swipeVibe(false)
        assertEquals(listOf<VibeRequest>(VibeRequest.DomeSwipe(next = true), VibeRequest.DomeSwipe(next = false)), seen)
    }

    // An AI's vibe applies at once with no transition: it asks the navigator nothing, so nothing rolls.
    @Test
    fun setVibeAsksTheNavigatorNothing() = runTest {
        val (vm, session) = makeViewModelAndSession()
        val seen = mutableListOf<VibeRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.vibeRequests.collect { seen += it } }
        val other = mkMinimalVibe("Other")
        vm.actions.setVibe(other)
        advanceUntilIdle()
        assertEquals("Other", vm.vibeFlow.value.name, "sanity: the vibe applied")
        assertEquals(emptyList<VibeRequest>(), seen)
    }

    @Test
    fun theFeatureRelaysTheSessionsMoves() = runTest {
        val (vm, session) = makeViewModelAndSession()
        assertSame(session.vibeMoves, vm.vibeMoves)
    }

    @Test
    fun navStateFollowsSessionProgress() = runTest {
        val (vm, session) = makeViewModelAndSession()
        session.updateProgress(PlaybackProgress(50_000, 200_000))
        advanceUntilIdle()
        assertEquals(0.25f, vm.vibeNavFlow.value.progress)
        assertEquals(vm.vibeFlow.value.name, vm.vibeNavFlow.value.currentName)
    }

    // Previews and fakes read the default on every composition; it must not allocate a flow per read.
    @Test
    fun theStubNavFlowIsShared() {
        val vibe = mkMinimalVibe("A")
        val stub = FakePulsarFeature(listOf(vibe), vibe)
        assertSame(stub.vibeNavFlow, stub.vibeNavFlow)
        assertEquals(VibeNavState.EMPTY, stub.vibeNavFlow.value)
    }
}

private class NavTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class NavTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class NavTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class NavTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

package org.balch.orpheus.djapp.ai

import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixMove
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.OneDispatcher
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.SILENT_DB
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.mixer.MixerViewModel
import org.balch.orpheus.features.pulsar.mixer.TrackTuning

/** In the AI edition the TRACKS view edits the shared mix controller's layers instead of writing gain itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class TracksViaControllerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val gainWrites = CopyOnWriteArrayList<Pair<Int, Float>>()
    /** The thread of the first port write. */
    private val firstWriteThread = CompletableDeferred<String>()
    private val synth = SynthController().apply {
        setDelegates(
            setter = { id, v ->
                firstWriteThread.complete(Thread.currentThread().name)
                val t = id.symbol.removePrefix("track_").substringBefore('_').toIntOrNull()
                if (t != null && id.symbol.endsWith("_audition_gain")) gainWrites += t to v.asFloat()
                true
            },
            getter = { null },
        )
    }
    private val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide")))

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** MixerViewModel reads only [SynthEngine.peakFlow]; every other call is a no-op. */
    private val engine = Proxy.newProxyInstance(
        SynthEngine::class.java.classLoader,
        arrayOf(SynthEngine::class.java),
    ) { _, method, _ ->
        when {
            method.name == "getPeakFlow" -> MutableStateFlow(0f)
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Float::class.javaPrimitiveType -> 0f
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    } as SynthEngine

    private suspend fun TestScope.controller() =
        MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }

    private fun TestScope.viewModel(c: MixBenchController): MixerViewModel {
        val scope = FeatureCoroutineScope()
        val dispatchers = OneDispatcher(testDispatcher)
        val prefs = object : BaseAppPreferencesRepository() {
            private var saved = AppPreferences()
            override suspend fun load() = saved
            override suspend fun save(preferences: AppPreferences) { saved = preferences }
        }
        return MixerViewModel(
            engine = engine,
            synthController = synth,
            pulsarFeature = pulsar,
            persistence = FeatureStatePersistence(prefs, dispatchers, scope),
            engagementTracker = DefaultEngagementTracker(),
            restoreStrategy = RestoreStrategy.PRESET,
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            dispatcherProvider = dispatchers,
            scope = scope,
            trackGainSink = MixBenchTrackGainSink(collabMixer(c, backgroundScope), { c }, pulsar, scope),
        ).also { vm -> backgroundScope.launch { vm.stateFlow.collect {} } }
    }

    private fun collabMixer(c: MixBenchController, scope: CoroutineScope, resolve: () -> MixBenchController = { c }) =
        CollabMixer(resolve, { pulsar }, MixCollab(), scope)

    private fun db(gain: Float) = 20f * log10(gain)

    @Test
    fun tracksViewRoutesThroughController() = runTest(testDispatcher) {
        val c = controller()
        val vm = viewModel(c)
        advanceUntilIdle()
        // Nothing seats unity on construction: the controller writes only what it owns.
        assertEquals(emptyList(), gainWrites.toList())

        vm.actions.setTrackLevel(2, 0.5f)
        advanceUntilIdle()

        assertEquals(db(0.5f), c.layers.value.vibe.levelDb[2], 1e-4f)
        assertEquals(setOf(2), c.ownedTracks())
        // The only gain write is the controller's own, for the track it now owns.
        assertEquals(listOf(2), gainWrites.map { it.first })
        assertEquals(0.5f, gainWrites.single().second, 1e-5f)
        assertEquals(0.5f, vm.stateFlow.value.tuning.levels[2], 1e-5f)
    }

    @Test
    fun constructionNeverResolvesTheController() = runTest(testDispatcher) {
        val c = controller()
        var resolved = 0
        val provider = { resolved++; c }
        val sink = MixBenchTrackGainSink(collabMixer(c, backgroundScope, provider), provider, pulsar, backgroundScope)
        // MixerViewModel builds under Metro's lock, so the AppScope controller waits for a coroutine.
        assertEquals(0, resolved)
        sink.toggleMute(0)
        // The consumer runs in backgroundScope, which advanceUntilIdle leaves alone.
        runCurrent()
        assertEquals(1, resolved)
        assertEquals(setOf(0), c.muted.value)
    }

    @Test
    fun aThrowingEditLeavesTheConsumerRunning() = runTest(testDispatcher) {
        val c = controller()
        val failures = CopyOnWriteArrayList<Throwable>()
        // FeatureCoroutineScope is a SupervisorJob, so a dead consumer would drop later taps silently.
        val scope = CoroutineScope(SupervisorJob() + testDispatcher + CoroutineExceptionHandler { _, e -> failures += e })
        try {
            val sink = MixBenchTrackGainSink(collabMixer(c, scope), { c }, pulsar, scope)
            sink.toggleMute(TrackTuning.TRACK_COUNT)
            sink.toggleMute(0)
            runCurrent()
            assertEquals(setOf(0), c.muted.value)
            assertEquals(emptyList(), failures.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theAiSinkRunsEditsOnTheDefaultDispatcherNotMain() = runTest(testDispatcher) {
        val c = controller()
        val edits = Executors.newSingleThreadExecutor { Thread(it, "tracks-edits") }
        val dispatchers = object : DispatcherProvider by OneDispatcher(testDispatcher) {
            override val default = edits.asCoroutineDispatcher()
        }
        val scope = FeatureCoroutineScope()
        try {
            val sink = AiTrackGainSink(collabMixer(c, scope), { c }, pulsar, scope, dispatchers)
            // The app's exit blocks Main on the controller's stop, so an edit bound to Main could stall it.
            sink.setLevel(1, 0.5f)
            // Coroutine debug mode appends " @coroutine#N" to the thread name.
            val thread = withContext(Dispatchers.IO) { withTimeout(5_000) { firstWriteThread.await() } }
            assertTrue(thread.startsWith("tracks-edits"), thread)
        } finally {
            scope.cancel()
            edits.shutdown()
        }
    }

    @Test
    fun muteAndSoloFromTracksAreControllerEdits()= runTest(testDispatcher) {
        val c = controller()
        val vm = viewModel(c)
        advanceUntilIdle()

        vm.actions.toggleTrackMute(1)
        vm.actions.toggleTrackSolo(4)
        advanceUntilIdle()

        assertEquals(setOf(1), c.muted.value)
        assertEquals(setOf(4), c.soloed.value)
        val tuning = vm.stateFlow.value.tuning
        assertTrue(tuning.muted[1])
        assertTrue(tuning.soloed[4])
        assertEquals(0f, tuning.gain(3))
    }

    @Test
    fun controllerEditsMoveTheTracksFaders() = runTest(testDispatcher) {
        val c = controller()
        val vm = viewModel(c)
        advanceUntilIdle()

        // An MCP or agent edit: the TRACKS view follows it.
        c.setHeardLevelDb(3, db(1.25f))
        c.toggleMute(6)
        advanceUntilIdle()

        val tuning = vm.stateFlow.value.tuning
        assertEquals(1.25f, tuning.levels[3], 1e-5f)
        assertTrue(tuning.muted[6])
    }

    @Test
    fun theFaderShowsTheLevelHeardInThePlayingSection() = runTest(testDispatcher) {
        pulsar.section(1)
        val c = controller()
        val vm = viewModel(c)
        c.setScope(Scope.SECTION)
        // The agent's section-scope move: the fader shows vibe plus section, so only in that section.
        c.setHeardLevelDb(0, db(0.5f))
        advanceUntilIdle()

        assertEquals(db(0.5f), c.layers.value.sections.getValue(1).levelDb[0], 1e-4f)
        assertEquals(0.5f, vm.stateFlow.value.tuning.levels[0], 1e-5f)
        pulsar.section(2)
        advanceUntilIdle()
        assertEquals(TrackTuning.UNITY, vm.stateFlow.value.tuning.levels[0])
    }

    @Test
    fun tracksEditsLandInTheVibeLayerWhateverTheAgentScope() = runTest(testDispatcher) {
        pulsar.section(1)
        val c = controller()
        val vm = viewModel(c)
        c.setScope(Scope.SECTION)
        advanceUntilIdle()

        vm.actions.setTrackLevel(0, 0.5f)
        advanceUntilIdle()

        assertEquals(db(0.5f), c.layers.value.vibe.levelDb[0], 1e-4f)
        assertEquals(0f, c.layers.value.sections[1]?.levelDb?.get(0) ?: 0f)
        // The agent's scope is left as it set it.
        assertEquals(Scope.SECTION, c.scope.value)
        pulsar.section(2)
        advanceUntilIdle()
        assertEquals(0.5f, vm.stateFlow.value.tuning.levels[0], 1e-5f)
    }

    @Test
    fun aClosedFaderIsTrueSilence() = runTest(testDispatcher) {
        val c = controller()
        val vm = viewModel(c)
        advanceUntilIdle()

        vm.actions.setTrackLevel(5, 0f)
        advanceUntilIdle()

        assertEquals(5 to 0f, gainWrites.last())
        assertEquals(SILENT_DB, c.layers.value.vibe.levelDb[5])
        assertEquals(0f, vm.stateFlow.value.tuning.levels[5])
    }

    @Test
    fun resetClearsOnlyTheTracksEditsInTheController() = runTest(testDispatcher) {
        pulsar.section(1)
        val c = controller()
        val vm = viewModel(c)
        // The agent's own edits: a pan and a send for the whole vibe, then a section send and level.
        c.setPan(3, -0.5f)
        c.setReverb(3, 0.4f)
        c.setScope(Scope.SECTION)
        c.setReverb(6, 0.2f)
        c.setHeardLevelDb(0, db(0.5f))
        advanceUntilIdle()
        vm.actions.setTrackLevel(2, 1.3f)
        vm.actions.toggleTrackMute(1)
        vm.actions.toggleTrackSolo(4)
        advanceUntilIdle()

        vm.actions.resetTracks()
        advanceUntilIdle()

        val l = c.layers.value
        assertEquals(List(8) { 0f }, l.vibe.levelDb)
        assertEquals(List(8) { 0f }, l.sections.getValue(1).levelDb)
        assertEquals(-0.5f, l.vibe.pan[3])
        assertEquals(0.4f, l.vibe.reverbRatio[3])
        assertEquals(0.2f, l.sections.getValue(1).reverbSend[6])
        assertEquals(emptySet(), c.muted.value)
        assertEquals(emptySet(), c.soloed.value)
        assertEquals(TrackTuning(), vm.stateFlow.value.tuning)
        // The solo owned every track; the reset hands them all back, so a section change writes nothing.
        assertEquals(emptySet(), c.ownedTracks())
        gainWrites.clear()
        pulsar.section(2)
        runCurrent()
        assertEquals(emptyList(), gainWrites.toList())
    }

    @Test
    fun tracksResetIsARecordedUserMove() = runTest(testDispatcher) {
        val c = controller()
        val collab = MixCollab()
        val moves = mutableListOf<MixMove>()
        backgroundScope.launch { collab.moves.collect { moves += it } }
        val sink = MixBenchTrackGainSink(CollabMixer({ c }, { pulsar }, collab, backgroundScope), { c }, pulsar, backgroundScope)
        sink.setLevel(2, 0.5f)
        runCurrent()
        sink.reset()
        runCurrent()
        assertEquals(0f, c.layers.value.vibe.levelDb[2])
        val move = moves.last()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.ResetToFile, move.control)
        assertEquals("edited" to "file", move.before to move.after)
    }
}

package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins PulsarViewModel's wiring of SectionQueue: the port write, arrival, the outro gate, vibe load. */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarQueueSectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()
    private val songEnding = StubSongEndingEventSource()
    private val engine = SongEndingStubSynthEngine()

    private val vibe: Vibe = mkMinimalVibe("Queue").copy(
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(Section(name = "intro"), Section(name = "verse"), Section(name = "chorus")),
        ),
    )

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(QueueTestAudioEngine())
        val dispatchers = QueueTestDispatchers(testDispatcher)
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), dispatchers),
            globalTempo = tempo,
            appPreferencesRepository = QueueTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = dispatchers,
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(QueueTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = songEnding,
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }

    private fun requestPort(): PortValue? {
        val id = PulsarSymbol.ARRANGEMENT_SECTION_REQUEST.controlId
        return ports["${id.uri}:${id.symbol}"]
    }

    private fun pushSection(index: Int) {
        engine.pulsarArrangementStateFlow.value = PulsarArrangementState(
            sectionIndex = index, barsElapsed = 0, barsTotal = 4,
            soloActive = false, soloTrack = -1, soloMode = 0,
        )
    }

    @Test
    fun `queueSection writes the request port and arrival clears the queued section`() =
        runTest(testDispatcher) {
            val vm = makeViewModel()
            vm.applyVibe(vibe)
            pushSection(0)
            advanceUntilIdle()

            vm.queueSection(2)
            assertEquals(PortValue.IntValue(3), requestPort(), "port carries index + 1")
            assertEquals(2, vm.queuedSectionFlow.value)

            pushSection(1); advanceUntilIdle()
            assertEquals(2, vm.queuedSectionFlow.value, "another section keeps the request queued")

            pushSection(2); advanceUntilIdle()
            assertEquals(-1, vm.queuedSectionFlow.value, "arrival clears the queued section")
        }

    @Test
    fun `an armed outro blocks the request`() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.applyVibe(vibe)
        pushSection(0)
        advanceUntilIdle()
        songEnding.endingTriggeredFlow.value = true

        vm.queueSection(2)
        assertNull(requestPort())
        assertEquals(-1, vm.queuedSectionFlow.value)
    }

    @Test
    fun `applying a vibe drops the queued section`() = runTest(testDispatcher) {
        val vm = makeViewModel()
        vm.applyVibe(vibe)
        pushSection(0)
        advanceUntilIdle()
        vm.queueSection(1)
        assertEquals(1, vm.queuedSectionFlow.value)

        vm.applyVibe(vibe)
        assertEquals(-1, vm.queuedSectionFlow.value)
    }
}

private class QueueTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class QueueTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class QueueTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class QueueTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

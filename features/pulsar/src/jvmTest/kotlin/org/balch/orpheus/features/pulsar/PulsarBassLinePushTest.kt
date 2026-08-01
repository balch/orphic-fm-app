package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the applyVibe push of the bass-line channel (Task 3): [Vibe.bassLine] steps,
 * mutation, octave, loop length, and step-count fence must all reach the
 * SynthController, and every track's `track_lick_source_N` symbol must reflect
 * whether that track renders the bass channel ([LickSource.BASS]) or the lead
 * channel ([LickSource.LEAD], the default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarBassLinePushTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun makeViewModel(vibe: Vibe): PulsarViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    ports["${id.uri}:${id.symbol}"] = value
                    true
                },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val tempo = GlobalTempo(BassPushTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), BassPushTestDispatchers(testDispatcher)),
            globalTempo = tempo,
            appPreferencesRepository = BassPushTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = BassPushTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(BassPushTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    private fun floatPort(symbol: String): Float? =
        (ports["$PULSAR_URI:$symbol"] as? PortValue.FloatValue)?.value

    @Test
    fun `bass line and track sources reach the controller`() = runTest(testDispatcher) {
        val bassLine = Lick(
            steps = listOf(LickStep(7, 1.5f, 0.9f), LickStep(-1, 0.5f, 0f), LickStep(4, 2.0f, 0.8f)),
            loopLength = 16,
        )
        val vibe = bassPushTestVibe(
            track3 = TrackRole.Melodic(lickMode = LickMode.Fill, lickSource = LickSource.BASS),
            track4 = TrackRole.Melodic(lickMode = LickMode.Fill), // lead stays LEAD
            bassLine = bassLine,
            bassLineMutation = 0.25f,
            bassLineOctave = 2,
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Step 0 fields (stride 4: degree, duration, velocity, glide)
        assertEquals(7f, floatPort("bass_line_data_0"))
        assertEquals(1.5f, floatPort("bass_line_data_1"))
        assertEquals(0.9f, floatPort("bass_line_data_2"))
        assertEquals(-1f, floatPort("bass_line_data_3"), "default glide sentinel must pass through")
        // Rest step marshals its negative degree
        assertEquals(-1f, floatPort("bass_line_data_4"))
        // Scalars + fence
        assertEquals(0.25f, floatPort("bass_line_mutation"))
        assertEquals(2f, floatPort("bass_line_octave"))
        assertEquals(16f, floatPort("bass_line_loop"))
        assertEquals(3f, floatPort("bass_line_length"))
        // Per-track source flags
        assertEquals(1f, floatPort("track_lick_source_3"), "bass track pushes 1")
        assertEquals(0f, floatPort("track_lick_source_4"), "lead track pushes 0")
        assertEquals(0f, floatPort("track_lick_source_0"), "percussive track pushes 0")
    }

    @Test
    fun `absent bass line pushes length zero`() = runTest(testDispatcher) {
        val vibe = bassPushTestVibe() // no bassLine, no BASS tracks
        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()
        assertEquals(0f, floatPort("bass_line_length"))
    }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun bassPushTestVibe(
    track3: TrackRole = TrackRole.Percussive,
    track4: TrackRole = TrackRole.Percussive,
    bassLine: Lick? = null,
    bassLineMutation: Float = 0.5f,
    bassLineOctave: Int = -1,
): Vibe = Vibe(
    name = "Bass Line Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    bassLine = bassLine,
    bassLineMutation = bassLineMutation,
    bassLineOctave = bassLineOctave,
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = when (it) {
                3 -> track3
                4 -> track4
                else -> TrackRole.Percussive
            },
        )
    },
)

private class BassPushTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class BassPushTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class BassPushTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class BassPushTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

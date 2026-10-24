package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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
import org.balch.orpheus.features.pulsar.models.LickMutationMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the applyVibe push of [Vibe.lickMutationMode] to `lick_mutation_mode`: pushed on
 * every load, since the mode covers the pool and the bass line as well as the static lick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarLickMutationModePushTest {

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
        val tempo = GlobalTempo(ModeTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), ModeTestDispatchers(testDispatcher)),
            globalTempo = tempo,
            appPreferencesRepository = ModeTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = ModeTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(ModeTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }

    private fun modePort(): Int? = when (val v = ports["$PULSAR_URI:lick_mutation_mode"]) {
        is PortValue.IntValue -> v.value
        is PortValue.FloatValue -> v.value.toInt()
        else -> null
    }

    @Test
    fun `lickMutationMode pushes 0 by default and 1 for CHORD_TONES, even with no static lick`() =
        runTest(testDispatcher) {
            val random = modeTestVibe()
            makeViewModel(random).actions.setVibe(random)
            advanceUntilIdle()
            assertEquals(0, modePort(), "a vibe that leaves the mode alone pushes RANDOM")

            ports.clear()
            val chordTones = modeTestVibe(mode = LickMutationMode.CHORD_TONES)
            makeViewModel(chordTones).actions.setVibe(chordTones)
            advanceUntilIdle()
            assertEquals(1, modePort(), "a pool-only vibe still pushes CHORD_TONES")
        }

    @Test
    fun `lickMutationMode survives a JSON round trip`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val vibe = modeTestVibe(mode = LickMutationMode.CHORD_TONES)
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(LickMutationMode.CHORD_TONES, decoded.lickMutationMode)

        // JSON written before the field existed decodes to today's mutation.
        val legacy = json.encodeToString(Vibe.serializer(), modeTestVibe())
            .replace("\"lickMutationMode\":\"RANDOM\",", "")
        assertFalse(legacy.contains("lickMutationMode"), "fixture must drop the field")
        assertEquals(LickMutationMode.RANDOM, json.decodeFromString(Vibe.serializer(), legacy).lickMutationMode)
    }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

// No static lick: the lead plays from a one-figure rotation pool.
private fun modeTestVibe(mode: LickMutationMode = LickMutationMode.RANDOM): Vibe = Vibe(
    name = "Lick Mutation Mode Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    lickRotation = LickRotation(
        pool = listOf(Lick(steps = listOf(LickStep(0, 1f, 0.8f), LickStep(-1, 1f, 0f), LickStep(2, 2f, 0.8f)))),
    ),
    lickMutationMode = mode,
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it == 4) TrackRole.Melodic(lickMode = LickMode.Fill) else TrackRole.Percussive,
        )
    },
)

private class ModeTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name = VibeName(vibe.name)
}

private class ModeTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class ModeTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class ModeTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

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
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.GrainMode
import org.balch.orpheus.features.pulsar.models.GrainScatterMode
import org.balch.orpheus.features.pulsar.models.GrainVoicing
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

/**
 * Pins the applyVibe push of the Mist grain fields. C++ picks the EDM or SPACE port by
 * the live engine slot, so a field pushed to only one is dropped whenever the other
 * slot is playing. Each slot here carries different values to catch a crossed write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarGrainPushTest {

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
        val tempo = GlobalTempo(GrainTestAudioEngine())
        val engine = SongEndingStubSynthEngine()
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            pulsarSession = PulsarSession(engine, makeAppCoroutineScope(testDispatcher), GrainTestDispatchers(testDispatcher)),
            globalTempo = tempo,
            appPreferencesRepository = GrainTestPrefs(),
            presetLoader = PresetLoader(PortRegistry(emptySet()), tempo, controller),
            dispatcherProvider = GrainTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(GrainTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
            musicPulseSource = MusicPulseSource.Silent,
        )
    }

    private fun int(symbol: String): Int? = (ports["$PULSAR_URI:$symbol"] as? IntValue)?.value
    private fun float(symbol: String): Float? = (ports["$PULSAR_URI:$symbol"] as? FloatValue)?.value

    // The Mixer may never be composed (DJ app, Mix tab closed): the load itself resets the engine.
    @Test
    fun vibeLoadResetsEngineGainsWithoutMixer() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)
        val vm = makeViewModel(vibe)
        advanceUntilIdle()
        for (t in 0..7) ports["$PULSAR_URI:track_${t}_audition_gain"] = FloatValue(0f)
        val before = vm.vibeLoadCount.value

        vm.actions.setVibe(vibe)
        advanceUntilIdle()

        for (t in 0..7) assertEquals(1f, float("track_${t}_audition_gain"), "track $t")
        assertEquals(before + 1, vm.vibeLoadCount.value)

        // Reloading the same vibe counts too.
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        assertEquals(before + 2, vm.vibeLoadCount.value)
    }

    @Test
    fun `grain fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(
            engineId = OrpheusEngineId.MST,
            grainMode = GrainMode.CONTINUOUS, grainScatterMode = GrainScatterMode.SCALE,
            grainVoicing = GrainVoicing.RISING,
            grainScatter = 0.4f, grainRate = 0.9f, grainRandomTiming = true,
            grainShape = 0.2f, grainBlend = 0.7f,
        )
        val space = OrpheusEngine(
            engineId = OrpheusEngineId.MST,
            grainMode = GrainMode.SINGLE, grainScatterMode = GrainScatterMode.RAW,
            grainVoicing = GrainVoicing.CENTERED,
            grainScatter = 0.1f, grainRate = 0.3f, grainRandomTiming = false,
            grainShape = 0.8f, grainBlend = 0.25f,
        )
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(300, int("track_4_engine_edm"), "the Mist engine id must reach the EDM slot")
        assertEquals(300, int("track_4_engine_space"), "the Mist engine id must reach the SPACE slot")
        assertEquals(2, int("track_4_grain_mode"))
        assertEquals(1, int("track_4_grain_mode_space"))
        assertEquals(1, int("track_4_grain_scatter_mode"))
        assertEquals(2, int("track_4_grain_scatter_mode_space"))
        assertEquals(2, int("track_4_grain_voicing"))
        assertEquals(1, int("track_4_grain_voicing_space"))
        assertEquals(0.4f, float("track_4_grain_scatter"))
        assertEquals(0.1f, float("track_4_grain_scatter_space"))
        assertEquals(0.9f, float("track_4_grain_rate"))
        assertEquals(0.3f, float("track_4_grain_rate_space"))
        assertEquals(1, int("track_4_grain_random_timing"))
        assertEquals(0, int("track_4_grain_random_timing_space"))
        assertEquals(0.2f, float("track_4_grain_shape"))
        assertEquals(0.8f, float("track_4_grain_shape_space"))
        assertEquals(0.7f, float("track_4_grain_blend"))
        assertEquals(0.25f, float("track_4_grain_blend_space"))
    }

    @Test
    fun `a non-Mist track pushes the grain defaults`() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0, int("track_0_grain_mode"))
        assertEquals(0, int("track_0_grain_voicing"))
        assertEquals(0.5f, float("track_0_grain_rate"))
        assertEquals(0.5f, float("track_0_grain_rate_space"))
        assertEquals(1f, float("track_0_grain_blend"))
        assertEquals(1f, float("track_0_grain_blend_space"))
    }

    @Test
    fun `grain FX fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.VA, grainFx = true, grainSize = 0.3f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.VA, grainFx = false, grainSize = 0.8f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(1, int("track_4_grain_fx"))
        assertEquals(0, int("track_4_grain_fx_space"))
        assertEquals(0.3f, float("track_4_grain_size"))
        assertEquals(0.8f, float("track_4_grain_size_space"))
    }

    @Test
    fun `a track without grain FX pushes fx 0 at the default size`() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0, int("track_0_grain_fx"))
        assertEquals(0, int("track_0_grain_fx_space"))
        assertEquals(0.5f, float("track_0_grain_size"))
        assertEquals(0.5f, float("track_0_grain_size_space"))
    }

    @Test
    fun `vibrato fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.MST, vibratoDepth = 35f, vibratoRate = 6.5f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.MST, vibratoDepth = 12f, vibratoRate = 3f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(35f, float("track_4_vibrato_depth"))
        assertEquals(12f, float("track_4_vibrato_depth_space"))
        assertEquals(6.5f, float("track_4_vibrato_rate"))
        assertEquals(3f, float("track_4_vibrato_rate_space"))
    }

    @Test
    fun `a track with no vibrato pushes depth 0 at the default rate`() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0f, float("track_0_vibrato_depth"))
        assertEquals(0f, float("track_0_vibrato_depth_space"))
        assertEquals(5.5f, float("track_0_vibrato_rate"))
        assertEquals(5.5f, float("track_0_vibrato_rate_space"))
    }

    @Test
    fun `pitch rise fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.MST, pitchRise = 350f, pitchRiseTime = 6.5f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.MST, pitchRise = 120f, pitchRiseTime = 3f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(350f, float("track_4_pitch_rise"))
        assertEquals(120f, float("track_4_pitch_rise_space"))
        assertEquals(6.5f, float("track_4_pitch_rise_time"))
        assertEquals(3f, float("track_4_pitch_rise_time_space"))
    }

    @Test
    fun `a track with no pitch rise pushes 0 at the default time`() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0f, float("track_0_pitch_rise"))
        assertEquals(0f, float("track_0_pitch_rise_space"))
        assertEquals(2f, float("track_0_pitch_rise_time"))
        assertEquals(2f, float("track_0_pitch_rise_time_space"))
    }

    @Test
    fun `harmonics rise fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.MST, harmonicsRise = 0.4f, harmonicsRiseTime = 6.5f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.MST, harmonicsRise = -0.3f, harmonicsRiseTime = 3f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0.4f, float("track_4_harmonics_rise"))
        assertEquals(-0.3f, float("track_4_harmonics_rise_space"))
        assertEquals(6.5f, float("track_4_harmonics_rise_time"))
        assertEquals(3f, float("track_4_harmonics_rise_time_space"))
    }

    @Test
    fun `morph rise fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.SPK, morphRise = 0.25f, morphRiseTime = 1.5f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.SPK, morphRise = -0.2f, morphRiseTime = 4f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0.25f, float("track_4_morph_rise"))
        assertEquals(-0.2f, float("track_4_morph_rise_space"))
        assertEquals(1.5f, float("track_4_morph_rise_time"))
        assertEquals(4f, float("track_4_morph_rise_time_space"))
        assertEquals(0f, float("track_0_morph_rise"), "a track without one pushes 0")
        assertEquals(2f, float("track_0_morph_rise_time"))
    }

    @Test
    fun `double fields reach both engine slots of their track`() = runTest(testDispatcher) {
        val edm = OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleDetune = 12f, doubleSpread = 0.4f)
        val space = OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleDetune = 0f, doubleSpread = 0.9f)
        val vibe = grainTestVibe(edm, space)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(12f, float("track_4_double_detune"))
        assertEquals(0f, float("track_4_double_detune_space"))
        assertEquals(0.4f, float("track_4_double_spread"))
        assertEquals(0.9f, float("track_4_double_spread_space"))
        assertEquals(0f, float("track_0_double_detune"), "a track without one pushes 0")
        assertEquals(0f, float("track_0_double_detune_space"))
        assertEquals(0f, float("track_0_double_spread"))
        assertEquals(0f, float("track_0_double_spread_space"))
    }

    @Test
    fun `a track with no harmonics rise pushes 0 at the default time`() = runTest(testDispatcher) {
        val va = OrpheusEngine(engineId = OrpheusEngineId.VA)
        val vibe = grainTestVibe(va, va)

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        assertEquals(0f, float("track_0_harmonics_rise"))
        assertEquals(0f, float("track_0_harmonics_rise_space"))
        assertEquals(2f, float("track_0_harmonics_rise_time"))
        assertEquals(2f, float("track_0_harmonics_rise_time_space"))
    }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun grainTestVibe(track4Edm: OrpheusEngine, track4Space: OrpheusEngine): Vibe = Vibe(
    name = "Grain Push Test",
    bpm = 120f,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f, ghostProbability = 0f,
        noteRangeLow = 36, noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    tracks = List(8) {
        TrackVoice(
            engineEdm = if (it == 4) track4Edm else OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = if (it == 4) track4Space else OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it == 4) TrackRole.Melodic() else TrackRole.Percussive,
        )
    },
)

private class GrainTestVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name = VibeName(vibe.name)
}

private class GrainTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class GrainTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class GrainTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

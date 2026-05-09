package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.ChordStep
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the per-section override push path in [PulsarViewModel.pushArrangement].
 * The reviewer flagged a (false-positive) concern that section-level
 * customProgression / chordsPerBar / compingHumanization were silently dropped;
 * these tests pin the behavior so a future refactor can't regress it.
 *
 * Each test loads a vibe whose Section carries a non-default override field,
 * then asserts that the corresponding `section_progression_*`,
 * `section_chords_per_bar_*`, or `section_comping_humanization_*` symbol
 * received the right value through the SynthController.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSectionProgressionPushTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()
    private val arrangementFlow =
        MutableStateFlow<PulsarArrangementState?>(ARRANGEMENT_STATE_UNKNOWN)

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
        val tempo = GlobalTempo(PushTestAudioEngine())
        val portRegistry = PortRegistry(emptySet())
        return PulsarViewModel(
            synthController = controller,
            synthEngine = PushTestSynthEngine(arrangementFlow),
            globalTempo = tempo,
            appPreferencesRepository = PushTestPrefs(),
            presetLoader = PresetLoader(portRegistry, tempo, controller),
            dispatcherProvider = PushTestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = setOf(PushTestVibeProvider(vibe)),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
        )
    }

    private fun port(symbol: String): PortValue? =
        ports["$PULSAR_URI:$symbol"]

    private fun intPort(symbol: String): Int? = (port(symbol) as? IntValue)?.value
    private fun floatPort(symbol: String): Float? = (port(symbol) as? FloatValue)?.value

    @Test
    fun `section customProgression and chordsPerBar reach controller`() = runTest(testDispatcher) {
        // Section 0: glide-rich progression with explicit chordsPerBar=4.
        // Section 1: no overrides — should serialize as length=0 / chordsPerBar=0.
        val s0Progression = listOf(
            ChordStep(0),
            ChordStep(3, glideRate = 0.45f),
            ChordStep(5),
            ChordStep(4),
        )
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "verse",
                    barsMin = 4, barsMax = 4,
                    customProgression = s0Progression,
                    chordsPerBar = 4,
                ),
                Section(name = "chorus", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Section 0: length encodes the override count; degree+glide arrays match.
        assertEquals(s0Progression.size, intPort("section_progression_active_0"),
            "section 0 progression length should be ${s0Progression.size}")
        assertEquals(4, intPort("section_chords_per_bar_0"),
            "section 0 chordsPerBar override should be 4")
        s0Progression.forEachIndexed { i, step ->
            assertEquals(step.degree, intPort("section_progression_degree_${i}"),
                "section 0 degree[$i]")
            assertEquals(step.glideRate, floatPort("section_progression_glide_${i}"),
                "section 0 glide[$i]")
        }

        // Section 1: no overrides — sentinels are 0 (no override) per engine.h:864/870.
        assertEquals(0, intPort("section_progression_active_1"),
            "section 1 progression length should be 0 (no override)")
        assertEquals(0, intPort("section_chords_per_bar_1"),
            "section 1 chordsPerBar override should be 0 (inherit vibe default)")
    }

    @Test
    fun `section compingHumanization override reaches controller`() = runTest(testDispatcher) {
        val humanization = CompingHumanization(
            dropProbability = 0.18f,
            ghostProbability = 0.22f,
            octaveJumpProbability = 0.12f,
            extensionProbability = 0.15f,
        )
        val vibe = pushTestVibe(
            sections = listOf(
                Section(
                    name = "loose",
                    barsMin = 4, barsMax = 4,
                    compingHumanization = humanization,
                ),
                Section(name = "tight", barsMin = 4, barsMax = 4),
            ),
        )

        makeViewModel(vibe).actions.setVibe(vibe)
        advanceUntilIdle()

        // Section 0: active=1, all four floats packed in order.
        assertEquals(1, intPort("section_comping_humanization_active_0"),
            "section 0 humanization active flag should be 1")
        assertEquals(humanization.dropProbability,    floatPort("section_comping_humanization_data_0"))
        assertEquals(humanization.ghostProbability,   floatPort("section_comping_humanization_data_1"))
        assertEquals(humanization.octaveJumpProbability, floatPort("section_comping_humanization_data_2"))
        assertEquals(humanization.extensionProbability,  floatPort("section_comping_humanization_data_3"))

        // Section 1: active=0; data slots stay at their initialized 0.
        assertEquals(0, intPort("section_comping_humanization_active_1"),
            "section 1 humanization active flag should be 0")
    }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun pushTestVibe(sections: List<Section>): Vibe = Vibe(
    name = "Section Push Test",
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
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
        )
    },
    arrangement = Arrangement(sections = sections),
)

private class PushTestVibeProvider(override val vibe: Vibe) : VibeProvider

private class PushTestAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class PushTestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class PushTestPrefs : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

// SynthEngine stub. Mirrors the no-op pattern used by PulsarSectionBpmTest.
private class PushTestSynthEngine(
    private val arrangement: MutableStateFlow<PulsarArrangementState?>,
) : SynthEngine {
    override val pulsarArrangementStateFlow: StateFlow<PulsarArrangementState?> get() = arrangement
    override fun start() = Unit
    override fun stop() = Unit
    override fun setVoiceTune(index: Int, tune: Float) = Unit
    override fun setVoiceGate(index: Int, active: Boolean) = Unit
    override fun setVoiceFeedback(index: Int, amount: Float) = Unit
    override fun setVoiceFmDepth(index: Int, amount: Float) = Unit
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) = Unit
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) = Unit
    override fun triggerDrum(type: Int, accent: Float) = Unit
    override fun setQuadPitch(quadIndex: Int, pitch: Float) = Unit
    override fun setQuadHold(quadIndex: Int, amount: Float) = Unit
    override fun setQuadVolume(quadIndex: Int, volume: Float) = Unit
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) = Unit
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = Unit
    override fun getQuadPitch(quadIndex: Int): Float = 0f
    override fun getQuadHold(quadIndex: Int): Float = 0f
    override fun getQuadVolume(quadIndex: Int): Float = 0f
    override fun getQuadTriggerSource(quadIndex: Int): Int = 0
    override fun getQuadPitchSource(quadIndex: Int): Int = 0
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int): Boolean = false
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = Unit
    override fun setVoiceHold(index: Int, amount: Float) = Unit
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) = Unit
    override fun setDrive(amount: Float) = Unit
    override fun setDistortionMix(amount: Float) = Unit
    override fun setMasterVolume(amount: Float) = Unit
    override fun setDelayTime(index: Int, time: Float) = Unit
    override fun setDelayFeedback(amount: Float) = Unit
    override fun setDelayMix(amount: Float) = Unit
    override fun setDelayModDepth(index: Int, amount: Float) = Unit
    override fun setHyperLfoFreq(index: Int, frequency: Float) = Unit
    override fun setHyperLfoMode(mode: Int) = Unit
    override fun setHyperLfoLink(active: Boolean) = Unit
    override fun getHyperLfoFreq(index: Int): Float = 0f
    override fun getHyperLfoMode(): Int = 0
    override fun getHyperLfoLink(): Boolean = false
    override fun setDuoModSource(duoIndex: Int, source: ModSource) = Unit
    override fun setFmStructure(crossQuad: Boolean) = Unit
    override fun setTotalFeedback(amount: Float) = Unit
    override fun setVibrato(amount: Float) = Unit
    override fun setVoiceCoupling(amount: Float) = Unit
    override fun setBend(amount: Float) = Unit
    override fun getBend(): Float = 0f
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) = Unit
    override fun releaseStringBend(stringIndex: Int): Int = 0
    override fun setSlideBar(yPosition: Float, xPosition: Float) = Unit
    override fun releaseSlideBar() = Unit
    override fun resetStringBenders() = Unit
    override fun playTestTone(frequency: Float) = Unit
    override fun stopTestTone() = Unit
    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
    override val peakFlow = MutableStateFlow(0f)
    override val cpuLoadFlow = MutableStateFlow(0f)
    override val voiceLevelsFlow = MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow = MutableStateFlow(0f)
    override val lfoAOutputFlow = MutableStateFlow(0f)
    override val lfoBOutputFlow = MutableStateFlow(0f)
    override val masterLevelFlow = MutableStateFlow(0f)
    override val bendFlow = MutableStateFlow(0f)
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean = false
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? = null
    override fun getVoiceTune(index: Int): Float = 0f
    override fun getVoiceFmDepth(index: Int): Float = 0f
    override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
    override fun getDuoSharpness(duoIndex: Int): Float = 0f
    override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
    override fun getFmStructureCrossQuad(): Boolean = false
    override fun getTotalFeedback(): Float = 0f
    override fun getVibrato(): Float = 0f
    override fun getVoiceCoupling(): Float = 0f
    override fun getDelayTime(index: Int): Float = 0f
    override fun getDelayFeedback(): Float = 0f
    override fun getDelayMix(): Float = 0f
    override fun getDelayModDepth(index: Int): Float = 0f
    override fun getDrive(): Float = 0f
    override fun getDistortionMix(): Float = 0f
    override fun getMasterVolume(): Float = 0f
    override fun setVoicePan(index: Int, pan: Float) = Unit
    override fun getVoicePan(index: Int): Float = 0f
    override fun setMasterPan(pan: Float) = Unit
    override fun getMasterPan(): Float = 0f
    override fun setStereoMode(mode: StereoMode) = Unit
    override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) = Unit
    override fun clearParameterAutomation(controlId: String) = Unit
    override fun getDrumFrequency(type: Int): Float = 0f
    override fun getDrumTone(type: Int): Float = 0f
    override fun getDrumDecay(type: Int): Float = 0f
    override fun getDrumP4(type: Int): Float = 0f
    override fun getDrumP5(type: Int): Float = 0f
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) = Unit
    override fun playTts() = Unit
    override fun stopTts() = Unit
    override fun isTtsPlaying(): Boolean = false
    override fun setLooperRecord(recording: Boolean) = Unit
    override fun setLooperPlay(playing: Boolean) = Unit
    override fun setLooperOverdub(overdub: Boolean) = Unit
    override fun setLooperQuantize(enabled: Boolean) = Unit
    override fun setLooperLevel(level: Float) = Unit
    override fun clearLooper() = Unit
    override fun getLooperPosition(): Float = 0f
    override fun getLooperDuration(): Double = 0.0
}

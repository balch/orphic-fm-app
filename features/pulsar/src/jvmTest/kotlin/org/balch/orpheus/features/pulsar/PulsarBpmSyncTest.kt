package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarBpmSyncTest {

    private class TestAudioEngine : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning: Boolean = false
        override val sampleRate: Int = 44100
        override fun getCpuLoad(): Float = 0f
        override fun getCurrentTime(): Double = 0.0
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher
    ) : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
        override val unconfined get() = dispatcher
    }

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Creates a SynthController with a port store.
     * @param presetBpm If non-null, pre-seeds the BPM port to simulate the engine
     *   having the plugin default (120) already stored — as happens in the real app.
     */
    private fun createSynthController(presetBpm: Float? = null): SynthController {
        val ports = mutableMapOf<String, PortValue>()
        if (presetBpm != null) {
            val bpmKey = "${PulsarSymbol.BPM.controlId.uri}:${PulsarSymbol.BPM.controlId.symbol}"
            ports[bpmKey] = FloatValue(presetBpm)
        }
        val controller = SynthController()
        controller.setDelegates(
            setter = { id, value ->
                ports["${id.uri}:${id.symbol}"] = value
                true
            },
            getter = { id ->
                ports["${id.uri}:${id.symbol}"]
            }
        )
        return controller
    }

    private fun makeViewModel(
        controller: SynthController,
        globalTempo: GlobalTempo,
        scope: FeatureCoroutineScope = FeatureCoroutineScope(),
    ): PulsarViewModel {
        val dispatchers = TestDispatcherProvider(testDispatcher)
        val portRegistry = PortRegistry(emptySet())
        return PulsarViewModel(
            synthController = controller,
            synthEngine = StubSynthEngine(),
            globalTempo = globalTempo,
            appPreferencesRepository = StubPreferences(),
            presetLoader = PresetLoader(portRegistry, globalTempo, controller),
            dispatcherProvider = dispatchers,
            scope = scope,
            vibeProviders = setOf(StubVibeProvider()),
            playbackMode = PulsarPlaybackMode.EXPLICIT,
        )
    }

    @Test
    fun `controlFlow seeds from engine getter - returns null when no stored value`() = runTest(testDispatcher) {
        val controller = createSynthController()

        val bpmFlow = controller.controlFlow(PulsarSymbol.BPM.controlId)
        println("BPM flow initial value: ${bpmFlow.value.asFloat()}")

        // controlFlow seeds from engine getter, which returns null -> defaults to FloatValue(0.5f)
        assertEquals(0.5f, bpmFlow.value.asFloat(), "controlFlow defaults to 0.5 when engine has no stored value")
    }

    @Test
    fun `GlobalTempo set to 90 before ViewModel creation - Pulsar state should show 90`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        // Simulate: user sets BPM to 90 via DrumBeats BEFORE Pulsar ViewModel is created
        globalTempo.setBpm(90.0)
        assertEquals(90.0, globalTempo.getBpm(), "GlobalTempo should be 90")

        val vm = makeViewModel(controller, globalTempo)

        // Advance coroutines so the init block's collect runs
        advanceUntilIdle()

        val state = vm.stateFlow.value
        println("State BPM after construction: ${state.bpm}")
        println("GlobalTempo BPM: ${globalTempo.getBpm()}")
        println("BPM controlFlow value: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")

        assertEquals(90f, state.bpm, "Pulsar BPM should sync to GlobalTempo's 90 at startup")
    }

    @Test
    fun `GlobalTempo change after ViewModel creation propagates to Pulsar state`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        val vm = makeViewModel(controller, globalTempo)

        advanceUntilIdle()
        println("Initial state BPM: ${vm.stateFlow.value.bpm}")

        // Now change GlobalTempo externally
        globalTempo.setBpm(90.0)
        advanceUntilIdle()

        val state = vm.stateFlow.value
        println("State BPM after GlobalTempo change to 90: ${state.bpm}")

        assertEquals(90f, state.bpm, "Pulsar BPM should update when GlobalTempo changes")
    }

    @Test
    fun `RACE - GlobalTempo at 90 before VM creation - stateFlow initialValue must be correct`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())
        globalTempo.setBpm(90.0)

        val vm = makeViewModel(controller, globalTempo)

        // DO NOT advance - check the stateFlow.value IMMEDIATELY after construction
        // This is what the UI sees on the first frame
        val immediateState = vm.stateFlow.value
        println("IMMEDIATE state BPM (no advance): ${immediateState.bpm}")
        println("BPM controlFlow value: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")

        assertEquals(90f, immediateState.bpm,
            "Pulsar stateFlow.value must be 90 IMMEDIATELY - the UI reads this on first frame")
    }

    @Test
    fun `REAL APP - engine has plugin default 120 but GlobalTempo is 90 - Pulsar must show 90`() = runTest(testDispatcher) {
        // This simulates the real app scenario:
        // 1. PulsarPlugin initializes with _bpm = 120 (plugin default)
        // 2. Engine stores this value
        // 3. Preset loads and sets GlobalTempo to 90
        // 4. PulsarViewModel is created lazily when panel renders
        // 5. controlFlow seeds bpmId from engine (120)
        // 6. But GlobalTempo is already at 90 — Pulsar must show 90

        val controller = createSynthController(presetBpm = 120f) // engine has plugin default
        val globalTempo = GlobalTempo(TestAudioEngine())
        globalTempo.setBpm(90.0) // preset already loaded

        val vm = makeViewModel(controller, globalTempo)

        // Check IMMEDIATELY - no advanceUntilIdle
        val immediateState = vm.stateFlow.value
        println("Engine BPM port: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")
        println("GlobalTempo BPM: ${globalTempo.getBpm()}")
        println("IMMEDIATE state BPM: ${immediateState.bpm}")

        assertEquals(90f, immediateState.bpm,
            "Even though engine has 120, Pulsar must show GlobalTempo's 90 on first frame")
    }

    @Test
    fun `Pulsar setBpm action propagates to GlobalTempo`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        val vm = makeViewModel(controller, globalTempo)

        advanceUntilIdle()

        // User adjusts Pulsar BPM knob to 140
        vm.actions.setBpm(140f)
        advanceUntilIdle()

        println("GlobalTempo after Pulsar setBpm(140): ${globalTempo.getBpm()}")
        assertEquals(140.0, globalTempo.getBpm(), "GlobalTempo should update when Pulsar BPM knob changes")

        println("Pulsar state BPM: ${vm.stateFlow.value.bpm}")
        assertEquals(140f, vm.stateFlow.value.bpm, "Pulsar state should reflect the new BPM")
    }
}

// ─── Stubs ────────────────────────────────────────────────────────────────────
//
// Wide enough to satisfy PulsarViewModel's constructor without dragging in
// real audio/preferences infrastructure. Methods that the BPM-sync tests
// don't exercise return zero/no-op.

private class StubVibeProvider : VibeProvider {
    override val vibe: Vibe = Vibe(
        name = "Test",
        bpm = 120f,
        rootNote = RootNote.C,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f,
            ghostProbability = 0f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        tracks = List(8) {
            TrackVoice(
                engineEdm = OrpheusEngineId.VIRTUAL_ANALOG,
                engineSpace = OrpheusEngineId.VIRTUAL_ANALOG,
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
    )
}

private class StubPreferences : AppPreferencesRepository {
    private var prefs = AppPreferences()
    override suspend fun load(): AppPreferences = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

private class StubSynthEngine : SynthEngine {
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
    override val peakFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val cpuLoadFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val voiceLevelsFlow = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoAOutputFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoBOutputFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val masterLevelFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val bendFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
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

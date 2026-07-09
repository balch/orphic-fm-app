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
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.models.Arrangement
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
import org.balch.orpheus.features.pulsar.vibes.FireSkyVibe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the per-section bpmMultiplier implementation in [PulsarViewModel]:
 * each section transition rescales live BPM by (newMult / previousMult), so
 * user-dialed tempo edits compose through multipliers across sections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSectionBpmTest {

    private val testDispatcher = StandardTestDispatcher()
    private val arrangementFlow = MutableStateFlow<PulsarArrangementState?>(ARRANGEMENT_STATE_UNKNOWN)
    private val stubEngine = ArrangementStubEngine(arrangementFlow)

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun pushSection(index: Int, barsElapsed: Int = 0, barsTotal: Int = 8) {
        arrangementFlow.value = PulsarArrangementState(
            sectionIndex = index,
            barsElapsed = barsElapsed,
            barsTotal = barsTotal,
            soloActive = false,
            soloTrack = -1,
            soloMode = 0,
        )
    }

    private fun assertBpmNear(expected: Double, actual: Double, label: String) {
        assertTrue(
            abs(expected - actual) < 0.01,
            "$label — expected ~$expected, got $actual"
        )
    }

    private fun makeViewModel(
        vibe: Vibe,
        globalTempo: GlobalTempo,
        providers: Set<VibeProvider> = setOf(SectionedVibeProvider(vibe)),
        prefs: AppPreferencesRepository = StubPrefs(),
        engine: SynthEngine = stubEngine,
    ): PulsarViewModel {
        val controller = SynthController().apply {
            val ports = mutableMapOf<String, PortValue>()
            setDelegates(
                setter = { id, value ->
                    ports["${id.uri}:${id.symbol}"] = value
                    true
                },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val portRegistry = PortRegistry(emptySet())
        return PulsarViewModel(
            synthController = controller,
            synthEngine = engine,
            globalTempo = globalTempo,
            appPreferencesRepository = prefs,
            presetLoader = PresetLoader(portRegistry, globalTempo, controller),
            dispatcherProvider = TestDispatchers(testDispatcher),
            scope = FeatureCoroutineScope(),
            vibeProviders = providers,
            playbackMode = PulsarPlaybackMode.EXPLICIT,
            songEndingPreferences = StubSongEndingPreferences(),
            transitionPreferences = StubTransitionPreferences(),
            transitionRunner = StubTransitionRunner(),
            songEndingEventSource = StubSongEndingEventSource(),
            engagementTracker = DefaultEngagementTracker(),
        )
    }

    @Test
    fun `section transition halves BPM via 0_5x multiplier`() = runTest(testDispatcher) {
        val vibe = sectionedVibe(
            bpm = 120f,
            mults = listOf(1.0f, 0.5f, 1.0f),
        )
        val tempo = GlobalTempo(StubAudioEngine())
        val vm = makeViewModel(vibe, tempo)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()
        assertBpmNear(120.0, tempo.getBpm(), "after applyVibe, BPM should equal vibe.bpm")

        pushSection(0); advanceUntilIdle()
        assertBpmNear(120.0, tempo.getBpm(), "section 0 (mult=1.0) should not change BPM")

        pushSection(1); advanceUntilIdle()
        assertBpmNear(60.0, tempo.getBpm(), "section 1 (mult=0.5) should halve BPM")

        pushSection(2); advanceUntilIdle()
        assertBpmNear(120.0, tempo.getBpm(),
            "section 2 (mult=1.0) should restore BPM via the 1.0/0.5 rescale")
    }

    @Test
    fun `live BPM dial during a section composes through subsequent transitions`() = runTest(testDispatcher) {
        // Values chosen to stay inside GlobalTempo's 60..200 coercion range while
        // still making the "compose with live edit" semantics distinguishable from
        // the naive "vibe.bpm * mult" semantics.
        val vibe = sectionedVibe(
            bpm = 100f,
            mults = listOf(1.0f, 0.8f, 1.5f),
        )
        val tempo = GlobalTempo(StubAudioEngine())
        val vm = makeViewModel(vibe, tempo)
        vm.actions.setVibe(vibe)
        advanceUntilIdle()

        pushSection(0); advanceUntilIdle()
        pushSection(1); advanceUntilIdle()
        assertBpmNear(80.0, tempo.getBpm(), "section 1 (mult=0.8) should be 80")

        // User dials during the 0.8× section — implicit "1.0× base" is now 125.
        tempo.setBpm(100.0)
        advanceUntilIdle()

        pushSection(2); advanceUntilIdle()
        assertBpmNear(187.5, tempo.getBpm(),
            "section 2 (mult=1.5) should be 100 * (1.5/0.8) = 187.5, " +
                "preserving the user's live edit through the multiplier ratio " +
                "(naive vibe.bpm * mult would yield 150)")
    }

    @Test
    fun `FireSky half-time intro plays slow then drops back to exactly the vibe bpm`() =
        runTest(testDispatcher) {
            // Regression guard for the 0.72x (not 0.5x) choice: FireSky is 84 BPM, so a
            // literal 0.5x cold open would be 42 -> clamped to the 60 floor, and the drop
            // (restore-by-ratio, x 1/0.5) would then over-restore to 60*2 = 120, running
            // the whole electro body 36 BPM fast. 0.72x stays above the 60 floor so the
            // slow sections are ~60.48 and the drop restores cleanly to 84.
            val fireSky = org.balch.orpheus.features.pulsar.vibes.FireSkyVibe().vibe
            val tempo = GlobalTempo(StubAudioEngine())
            val vm = makeViewModel(fireSky, tempo)
            vm.actions.setVibe(fireSky)
            advanceUntilIdle()
            assertBpmNear(60.48, tempo.getBpm(),
                "applyVibe now applies the opening section's 0.72× itself (84 * 0.72), so the " +
                    "intro is half-time immediately — no dependence on a section-0 emission")

            pushSection(0); advanceUntilIdle()  // intro (cold open), mult 0.72 — idempotent re-emit
            assertBpmNear(60.48, tempo.getBpm(),
                "cold open (0.72x) should be ~60.48 BPM — above the 60 floor, not clamped")

            pushSection(1); advanceUntilIdle()  // build, also mult 0.72
            assertBpmNear(60.48, tempo.getBpm(),
                "build (same 0.72x) should hold the half-time tempo, no change")

            pushSection(2); advanceUntilIdle()  // verse — THE DROP, mult 1.0
            assertBpmNear(84.0, tempo.getBpm(),
                "drop into verse must restore EXACTLY 84 (a literal 0.5x would give 120)")
        }

    // NOTE: the exit-scratch is now triggered and held entirely in C++ (the pulsar unit
    // arms the master scratch at the section flip and freezes its clock while active).
    // The Kotlin VM no longer calls masterScratch, so that behavior is covered by the C++
    // test `test_master_scratch_freezes_pulsar_clock` in test_pulsar_sections.cpp.

    @Test
    fun `switching from a fast vibe already on section 0 into FireSky still opens at half-time`() =
        runTest(testDispatcher) {
            // Reproduces the production bug: the prior vibe sits on section 0, so the
            // section-BPM collector's distinctUntilChanged suppresses FireSky's section-0
            // re-emission — applyVibe itself must apply the intro's 0.53×, not the collector.
            val tempo = GlobalTempo(StubAudioEngine())
            val vFast = sectionedVibe(bpm = 160f, mults = listOf(1.0f, 1.0f))
            val fireSky = org.balch.orpheus.features.pulsar.vibes.FireSkyVibe().vibe

            val vm = makeViewModel(vFast, tempo)
            vm.actions.setVibe(vFast); advanceUntilIdle()
            pushSection(0); advanceUntilIdle()   // leaves the flow parked on sectionIndex 0
            assertBpmNear(160.0, tempo.getBpm(), "vFast section 0 (mult 1.0) = 160")

            // Switch into FireSky WITHOUT any section re-emission (no -1/0 push): production
            // has none, and distinctUntilChanged would swallow a 0→0 poll anyway.
            vm.actions.setVibe(fireSky); advanceUntilIdle()
            assertBpmNear(60.48, tempo.getBpm(),
                "FireSky must open at ~60.48 (84 * 0.72) even when the prior vibe was on " +
                    "section 0 and no section re-emission arrives")
        }

    @Test
    fun `vibe reload resets section-mult tracker so next section composes against vibe_bpm`() =
        runTest(testDispatcher) {
            val tempo = GlobalTempo(StubAudioEngine())
            val v1 = sectionedVibe(bpm = 120f, mults = listOf(1.0f, 0.5f))
            val v2 = sectionedVibe(bpm = 100f, mults = listOf(1.5f, 1.0f))

            val vm = makeViewModel(v1, tempo)
            vm.actions.setVibe(v1); advanceUntilIdle()
            pushSection(0); advanceUntilIdle()
            pushSection(1); advanceUntilIdle()
            assertBpmNear(60.0, tempo.getBpm(), "v1 section 1 should be 60")

            // Switching vibes: applyVibe(v2) applies v2's OPENING section mult (1.5) itself
            // and seeds lastSectionMult=1.5, so BPM lands at 100 * 1.5 = 150 immediately and
            // v1's 0.5× cannot leak forward.
            vm.actions.setVibe(v2); advanceUntilIdle()
            assertBpmNear(150.0, tempo.getBpm(),
                "applyVibe(v2) applies v2's intro mult 1.5 → 100 * 1.5 = 150 (no leak of v1's 0.5×)")

            // A subsequent section-0 emission is idempotent (newMult == seeded oldMult 1.5),
            // so it must NOT re-scale. pushSection(-1) is filtered out before
            // distinctUntilChanged, so the following 0 still emits (1→0).
            pushSection(-1); advanceUntilIdle()
            pushSection(0); advanceUntilIdle()
            assertBpmNear(150.0, tempo.getBpm(),
                "v2 section 0 (mult 1.5) re-emit is idempotent — stays 150")
        }

    @Test
    fun `restoring a saved half-time-intro vibe opens at half-time, not the flat saved bpm`() =
        runTest(testDispatcher) {
            // Production repro of the "FireSky starts at full tempo on launch" bug. FireSky is
            // the saved/default vibe and the previous session persisted the BODY tempo unchanged
            // (savedBpm is derived from the live vibe, not hardcoded, so this test can't go stale
            // the next time FireSky's bpm is retuned — see PulsarSectionBpmTest history).
            // On startup restoreSavedState() re-applies the saved bpm; if it ignores the opening
            // section's 0.72x multiplier it restores the ~60 BPM half-time intro at the full
            // body tempo instead. bpmId feeds the C++ pulsar clock's live override, so this is
            // audible.
            //
            // graphReady is left PENDING so the VM's post-graph vibe re-apply (which re-seeds the
            // intro tempo from vibe.bpm) can't mask the restore push — isolating the unit under
            // test. In MIX_GATED that re-apply is itself re-clobbered by the presetFlow-driven
            // second restore, so in production the (buggy) restore push is the last writer anyway.
            val savedBpm = FireSkyVibe().vibe.bpm
            val savedJson = persistJson.encodeToString(
                PulsarUiState(
                    vibe = sectionedVibe(bpm = savedBpm, mults = listOf(1.0f)),
                    vibeName = "Fire Sky",
                    bpm = savedBpm,
                )
            )
            val tempo = GlobalTempo(StubAudioEngine())
            val pendingGraph = ArrangementStubEngine(
                MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN),
                graphReadyDeferred = CompletableDeferred(),  // never completes: hold the re-apply
            )
            makeViewModel(
                vibe = FireSkyVibe().vibe,
                globalTempo = tempo,
                providers = setOf(FireSkyVibe()),
                prefs = StubPrefs(AppPreferences(lastPulsarJson = savedJson)),
                engine = pendingGraph,
            )
            advanceUntilIdle()

            assertBpmNear(60.48, tempo.getBpm(),
                "restore of FireSky (saved body bpm $savedBpm) must open the half-time intro " +
                    "at $savedBpm * 0.72 = 60.48 — not the flat saved $savedBpm")
        }
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

// Mirrors PulsarViewModel.persistJson so a hand-built saved state round-trips identically.
private val persistJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun sectionedVibe(bpm: Float, mults: List<Float>): Vibe {
    val sections = mults.mapIndexed { i, m ->
        Section(name = "s$i", barsMin = 4, barsMax = 4, bpmMultiplier = m)
    }
    return Vibe(
        name = "Test-${mults.joinToString(",")}-bpm$bpm",
        bpm = bpm,
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
}

private class SectionedVibeProvider(override val vibe: Vibe) : VibeProvider {
    override val name: String get() = vibe.name
}

private class StubAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning: Boolean = false
    override val sampleRate: Int = 44100
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
}

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

private class StubPrefs(initial: AppPreferences = AppPreferences()) : AppPreferencesRepository {
    private var prefs = initial
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        prefs = transform(prefs)
    }
}

// SynthEngine stub that exposes a writable arrangement flow so the test can
// drive section transitions. Everything else is a no-op.
private class ArrangementStubEngine(
    private val arrangement: MutableStateFlow<PulsarArrangementState?>,
    // Default: an already-completed graphReady, so the VM's post-graph vibe re-apply runs
    // (matches most tests). Pass a pending CompletableDeferred() to hold that re-apply back
    // and observe restoreSavedState()'s tempo push in isolation.
    private val graphReadyDeferred: kotlinx.coroutines.Deferred<Unit> =
        kotlinx.coroutines.CompletableDeferred(Unit),
) : SynthEngine {
    override val graphReady: kotlinx.coroutines.Deferred<Unit> get() = graphReadyDeferred
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
    override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) = Unit
    override fun masterTapeStop(durationMs: Int) = Unit
    override fun masterScratch(durationMs: Int) = Unit
    override fun masterFilter(durationMs: Int) = Unit
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

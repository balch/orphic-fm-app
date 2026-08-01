package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.MuteSink
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.playback.SongEndingPreferences
import org.balch.orpheus.features.pulsar.playback.TransitionPreferences

/**
 * Shared no-op stubs for the new song-ending dependencies that
 * `PulsarViewModel` constructor pulls in (Task 12 wiring). Lives here so the
 * three pre-existing PulsarViewModel test files (BpmSync / SectionBpm /
 * SectionProgressionPush) don't have to duplicate the stub plumbing.
 *
 * The fakes mirror the patterns in
 * `core/foundation/src/jvmTest/.../PlaybackControllerTest.kt` and
 * `features/pulsar/src/jvmTest/.../playback/PulsarSongEndingTest.kt`.
 */

internal class FakeMetadataProducer : MetadataProducer {
    override val titleFlow = MutableStateFlow("T")
    override val subtitleFlow = MutableStateFlow("S")
}

internal class StubSongEndingPreferences : SongEndingPreferences {
    override val enabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun setEnabled(value: Boolean) { enabledFlow.value = value }
}

internal class StubTransitionPreferences(
    initial: TransitionSpec = TransitionStyle.default,
) : TransitionPreferences {
    override val defaultFlow: MutableStateFlow<TransitionSpec> = MutableStateFlow(initial)
    override suspend fun setDefault(value: TransitionSpec) { defaultFlow.value = value }
}

/**
 * CUT-style stub runner — runTransition invokes applyNext synchronously with
 * no fades or delays. Used by ViewModel-construction tests that don't care
 * about transition timing but need to satisfy the PulsarTransitionRunner DI
 * dependency.
 */
internal class StubTransitionRunner : org.balch.orpheus.features.pulsar.playback.PulsarTransitionRunner {
    override val activeStyle: MutableStateFlow<TransitionStyle?> = MutableStateFlow(null)
    override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
        applyNext()
    }
}

/** Inert stub SongEndingEventSource — never emits, indexes stay at defaults. */
internal class StubSongEndingEventSource :
    org.balch.orpheus.features.pulsar.playback.SongEndingEventSource {
    override val songEndingEvents:
        kotlinx.coroutines.flow.SharedFlow<org.balch.orpheus.features.pulsar.playback.SongEndingEvent> =
        kotlinx.coroutines.flow.MutableSharedFlow()
    override val finalSectionIndex: kotlinx.coroutines.flow.StateFlow<Int> =
        MutableStateFlow(-1)
    override val endingTriggered: kotlinx.coroutines.flow.StateFlow<Boolean> =
        MutableStateFlow(false)
    override val resolvedTransitionStyle: kotlinx.coroutines.flow.StateFlow<TransitionStyle> =
        MutableStateFlow(TransitionStyle.FADE)
    override fun armOutro() { /* no-op for VM-construction tests */ }

    var vibeAppliedCount: Int = 0
        private set

    /** Runs inside onVibeApplied() so a test can snapshot surrounding state. */
    var onVibeAppliedObserver: () -> Unit = {}

    override fun onVibeApplied() {
        vibeAppliedCount++
        onVibeAppliedObserver()
    }
}

/**
 * Mutable in-memory [PulsarFeature] for unit tests. Exposes the four pieces
 * [PulsarSongEnding] and [PulsarSongAdvancer] consume: `vibeList`, `vibeFlow`,
 * `applyVibe()`, and `arrangementStateFlow`. All other [PulsarFeature] surfaces
 * are stubbed.
 */
internal class FakePulsarFeature(
    override val vibeList: List<Vibe>,
    initial: Vibe,
) : org.balch.orpheus.features.pulsar.PulsarFeature {
    override val vibeFlow: MutableStateFlow<Vibe> = MutableStateFlow(initial)
    val arrangement: MutableStateFlow<PulsarArrangementState> = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    override val arrangementStateFlow: kotlinx.coroutines.flow.StateFlow<PulsarArrangementState> = arrangement
    override val vibeNames: List<String> = vibeList.map { it.name }

    /**
     * Mirrors `PulsarViewModel.applyVibe()`'s `onVibeApplied()` call. Do not set
     * this by hand: build the pair with [makeSongEnding], which wires it.
     */
    var onVibeApplied: () -> Unit = {}

    override fun applyVibe(vibe: Vibe) {
        vibeFlow.value = vibe
        onVibeApplied()
    }
    override fun applyVibeByName(name: String): Boolean {
        val v = vibeList.firstOrNull { it.name == name } ?: return false
        applyVibe(v)
        return true
    }
    override val stateFlow = MutableStateFlow(PulsarUiState(vibe = initial))
    override val actions = PulsarPanelActions()
}

internal class FixturesDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun makeAppCoroutineScope(
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
): AppCoroutineScope = AppCoroutineScope(FixturesDispatchers(dispatcher))

/**
 * The only sanctioned way to build a real [PulsarSongEnding] over a
 * [FakePulsarFeature]. Wiring `feature.onVibeApplied` by hand is easy to forget,
 * and forgetting it silently disables the per-song reset the whole class exists
 * to perform. Also takes the [PulsarSession] [PulsarSongEnding] reads its flows from;
 * bridge `feature.vibeFlow` into it if a test needs the two in sync.
 */
internal fun makeSongEnding(
    feature: FakePulsarFeature,
    pulsarSession: PulsarSession,
    playbackController: PlaybackController,
    preferences: SongEndingPreferences,
    synthController: org.balch.orpheus.core.controller.SynthController,
    scope: AppCoroutineScope,
    transitionPreferences: TransitionPreferences = StubTransitionPreferences(),
): org.balch.orpheus.features.pulsar.playback.PulsarSongEnding =
    org.balch.orpheus.features.pulsar.playback.PulsarSongEnding(
        pulsarSession = pulsarSession,
        playbackController = playbackController,
        preferences = preferences,
        transitionPreferences = transitionPreferences,
        synthController = synthController,
        scope = scope,
    ).also { songEnding ->
        feature.onVibeApplied = { songEnding.onVibeApplied() }
    }

/**
 * Build a minimal, real [PulsarSongEnding] stack — `PulsarViewModel` only
 * touches `playbackController.state`, so the controller can sit at its
 * default `Stopped` and never receive any real audio plumbing wiring.
 */
internal fun makeStubPlaybackController(scope: AppCoroutineScope): PlaybackController =
    PlaybackController(
        mediaSessionManager = MediaSessionManager(),
        mediaSessionStateManager = MediaSessionStateManager(scope),
        playbackLifecycleManager = PlaybackLifecycleManager(),
        muteSink = MuteSink { _: PlaybackState -> },
        engagementTracker = DefaultEngagementTracker(),
        metadataProducer = FakeMetadataProducer(),
        scope = scope,
        overlayProducer = null,
        skipHandler = null,
        playFromMediaIdHandler = null,
    )

/**
 * Minimal valid [Vibe] for tests that only care about identity (name) and the
 * song-ending parameters. Fixture defaults are 150/300 (the historical
 * production defaults the existing tests were written against) — pass
 * [minVibeSeconds]/[maxVibeSeconds] to override. This decouples tests from
 * whatever the current production `Arrangement` defaults happen to be, so a
 * tuning change to those defaults doesn't silently break unit-test
 * assertions about trigger timing.
 *
 * Pass [transitionOut] to control the per-vibe transition spec (outroBars,
 * curve, style). Default null = inherit the [TransitionPreferences] default.
 * Mirrors the per-file `mkVibe` helper that `PulsarSongAdvancerTest` uses;
 * lifted here so the song-ending integration test can share it.
 */
internal fun mkMinimalVibe(
    name: String,
    transitionOut: TransitionSpec? = null,
    minVibeSeconds: Int = 150,
    maxVibeSeconds: Int = 300,
): Vibe = Vibe(
    name = name,
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
        )
    },
    bpm = 120f,
    rootNote = RootNote.A,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f,
        ghostProbability = 0f,
        noteRangeLow = 36,
        noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    arrangement = org.balch.orpheus.features.pulsar.models.Arrangement(
        sections = listOf(org.balch.orpheus.features.pulsar.models.Section(name = "loop")),
        transitionOut = transitionOut,
        lengthSeconds = minVibeSeconds..maxVibeSeconds,
    ),
)

/**
 * Mutable [SongEndingPreferences] for tests that need to flip the enabled
 * preference at runtime. Identical to the file-private `MutablePrefs` shape
 * used by `PulsarSongEndingTest`; lifted to fixtures for cross-test reuse.
 */
internal class MutablePrefs : SongEndingPreferences {
    override val enabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun setEnabled(value: Boolean) { enabledFlow.value = value }
}

/**
 * No-op [SynthEngine] for song-ending and advancer tests. Only
 * `setMasterVolume`/`getMasterVolume` and [pulsarArrangementStateFlow] carry real state.
 * Named to avoid colliding with the file-private `StubSynthEngine` in `PulsarBpmSyncTest.kt`.
 */
internal open class SongEndingStubSynthEngine : SynthEngine {
    @Volatile private var masterVolume: Float = 1.0f

    // Settable so tests can drive PulsarSession's arrangement-state producer, which now
    // enriches from this engine rather than from FakePulsarFeature's backing field.
    override val pulsarArrangementStateFlow = MutableStateFlow<PulsarArrangementState?>(null)

    override fun start() = Unit
    override fun stop() = Unit
    override fun setMasterVolume(amount: Float) { masterVolume = amount }
    override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) { masterVolume = target }
    override fun masterTapeStop(durationMs: Int) { masterVolume = 0f }
    override fun masterScratch(durationMs: Int) {}
    override fun masterFilter(durationMs: Int) {}
    override fun getMasterVolume(): Float = masterVolume

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

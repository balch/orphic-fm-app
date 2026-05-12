package org.balch.orpheus.core.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.MuteSink
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.plugin.PortValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SynthOrchestrator engine lifecycle.
 *
 * SynthOrchestrator now owns only:
 *  - Engine start/stop.
 *  - Routing RequestResume to PlaybackController.
 *  - Routing StopAll to MediaSessionStateManager.clearAll().
 *
 * MediaSession activation, pause/resume, and mode tracking were moved to
 * PlaybackController (tested in PlaybackControllerTest).
 */
class SynthOrchestratorTest {

    private fun createOrchestrator(): TestHarness {
        val scope = testScope()
        val engine = FakeSynthEngine()
        val playbackLifecycleManager = PlaybackLifecycleManager()
        val mediaSessionStateManager = MediaSessionStateManager(scope)
        val muteCalls = mutableListOf<PlaybackState>()
        val controller = PlaybackController(
            mediaSessionManager = MediaSessionManager(),
            mediaSessionStateManager = mediaSessionStateManager,
            playbackLifecycleManager = playbackLifecycleManager,
            muteSink = MuteSink { state -> muteCalls.add(state) },
            metadataProducer = FakeMetadata(),
            scope = scope,
        )
        val orchestrator = SynthOrchestrator(
            engine = engine,
            playbackLifecycleManager = playbackLifecycleManager,
            mediaSessionStateManager = mediaSessionStateManager,
            playbackController = controller,
            scope = scope,
        )
        return TestHarness(orchestrator, engine, mediaSessionStateManager, controller, muteCalls)
    }

    private data class TestHarness(
        val orchestrator: SynthOrchestrator,
        val engine: FakeSynthEngine,
        val mediaSessionStateManager: MediaSessionStateManager,
        val playbackController: PlaybackController,
        val muteCalls: MutableList<PlaybackState>,
    )

    // ═══════════════════════════════════════════════════════════
    // Engine lifecycle tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `start sets engine running`() = runTest {
        val h = createOrchestrator()

        h.orchestrator.start()

        assertTrue(h.engine.started, "Engine should be started")
    }

    @Test
    fun `start is idempotent across multiple call sites`() = runTest {
        // After moving engine start to DjMediaBrowserService.onCreate, both
        // the service AND DjApp's LaunchedEffect can call start() on the same
        // process. The second call must be a no-op — calling engine.start()
        // twice on miniaudio would error out, so the orchestrator's isStarted
        // guard is the contract that makes the multi-call-site safe.
        val h = createOrchestrator()

        h.orchestrator.start()
        val startCountAfterFirst = h.engine.startCount
        h.orchestrator.start()

        assertTrue(h.engine.started, "Engine should still be started")
        assertEquals(1, h.engine.startCount, "engine.start() should fire exactly once")
        assertEquals(startCountAfterFirst, h.engine.startCount, "Second start call must be a no-op")
    }

    @Test
    fun `stop shuts down engine`() = runTest {
        val h = createOrchestrator()
        h.orchestrator.start()

        h.orchestrator.stop()

        assertFalse(h.engine.started, "Engine should be stopped")
    }

    @Test
    fun `stop broadcasts StopAll which clears MediaSessionStateManager`() = runTest {
        val h = createOrchestrator()
        h.orchestrator.start()
        h.mediaSessionStateManager.setPulsarActive(true)
        assertTrue(h.mediaSessionStateManager.isMediaSessionNeeded.value, "Should be needed before stop")

        h.orchestrator.stop()

        assertFalse(h.mediaSessionStateManager.isMediaSessionNeeded.value, "clearAll should have cleared state")
    }

    @Test
    fun `RequestResume via shared PlaybackLifecycleManager calls play`() = runTest {
        val scope = testScope()
        val engine = FakeSynthEngine()
        val plm = PlaybackLifecycleManager()
        val mediaSessionStateManager = MediaSessionStateManager(scope)
        val muteCalls = mutableListOf<PlaybackState>()
        val controller = PlaybackController(
            mediaSessionManager = MediaSessionManager(),
            mediaSessionStateManager = mediaSessionStateManager,
            playbackLifecycleManager = plm,
            muteSink = MuteSink { state -> muteCalls.add(state) },
            metadataProducer = FakeMetadata(),
            scope = scope,
        )
        SynthOrchestrator(
            engine = engine,
            playbackLifecycleManager = plm,
            mediaSessionStateManager = mediaSessionStateManager,
            playbackController = controller,
            scope = scope,
        )

        controller.play()
        controller.pause()
        assertEquals(PlaybackState.Paused, controller.state.value)

        plm.tryRequestResume()

        assertEquals(PlaybackState.Playing, controller.state.value, "RequestResume should have called play()")
    }
}

private fun assertEquals(expected: PlaybackState, actual: PlaybackState, message: String = "") {
    if (expected != actual) throw AssertionError("Expected $expected but was $actual. $message")
}

private fun assertEquals(expected: Int, actual: Int, message: String = "") {
    if (expected != actual) throw AssertionError("Expected $expected but was $actual. $message")
}

// ─── Minimal Fakes ────────────────────────────────────────────────────────────

private class FakeMetadata : MetadataProducer {
    override val titleFlow = MutableStateFlow("T")
    override val subtitleFlow = MutableStateFlow("S")
}

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

private class FakeSynthEngine : SynthEngine {
    var started = false
    var startCount = 0
    private var _volume = 0.7f
    val volume get() = _volume

    override fun start() { started = true; startCount++ }
    override fun stop() { started = false }
    override fun getMasterVolume(): Float = _volume
    override fun setMasterVolume(amount: Float) { _volume = amount }

    // ── Stubs (never called by SynthOrchestrator) ──
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
    override val peakFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val cpuLoadFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val voiceLevelsFlow get() = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoAOutputFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val lfoBOutputFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val masterLevelFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val bendFlow get() = kotlinx.coroutines.flow.MutableStateFlow(0f)
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

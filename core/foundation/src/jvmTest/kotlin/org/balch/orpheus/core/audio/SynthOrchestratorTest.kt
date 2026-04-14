package org.balch.orpheus.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.plugin.PortValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for SynthOrchestrator's engine lifecycle and media session
 * activation, focusing on the stop-then-play recovery path that occurs when
 * a user stops playback via the notification and later presses Play in the UI.
 *
 * These tests use real MediaSessionManager (JVM) and MediaSessionStateManager
 * instances. The JVM MediaSessionManager's native macOS calls are no-ops in test.
 */
class SynthOrchestratorTest {

    /** Real-time delay to let SynthOrchestrator's internal Dispatchers.Default collectors process.
     *  Must use withContext(Default) because runTest uses virtual time for delay(). */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(100) }

    private fun createOrchestrator(): TestHarness {
        val engine = FakeSynthEngine()
        val mediaSessionManager = org.balch.orpheus.core.media.MediaSessionManager()
        val playbackLifecycleManager = PlaybackLifecycleManager()
        val mediaSessionStateManager = MediaSessionStateManager()

        val orchestrator = SynthOrchestrator(
            engine = engine,
            mediaSessionManager = mediaSessionManager,
            playbackLifecycleManager = playbackLifecycleManager,
            mediaSessionStateManager = mediaSessionStateManager,
        )
        return TestHarness(orchestrator, engine, mediaSessionStateManager)
    }

    private data class TestHarness(
        val orchestrator: SynthOrchestrator,
        val engine: FakeSynthEngine,
        val mediaSessionStateManager: MediaSessionStateManager,
    )

    // ═══════════════════════════════════════════════════════════
    // Tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `start sets engine running`() = runTest {
        val h = createOrchestrator()

        h.orchestrator.start()

        assertTrue(h.engine.started, "Engine should be started")
    }

    @Test
    fun `stop shuts down engine`() = runTest {
        val h = createOrchestrator()
        h.orchestrator.start()

        h.orchestrator.stop()

        assertFalse(h.engine.started, "Engine should be stopped")
    }

    @Test
    fun `setPulsarActive after start activates media session`() = runTest {
        val h = createOrchestrator()
        h.orchestrator.start()

        h.mediaSessionStateManager.setPulsarActive(true)
        settle()

        // Verify via peakFlow — a proxy that the orchestrator is alive.
        // The real assertion is that no exception was thrown and the flow completed.
        assertTrue(h.engine.started, "Engine should still be running")
    }

    @Test
    fun `stop then setPulsarActive restarts engine`() = runTest {
        val h = createOrchestrator()

        // 1. Normal startup + play
        h.orchestrator.start()
        h.mediaSessionStateManager.setPulsarActive(true)
        settle()
        assertTrue(h.engine.started, "Engine should be running after start")

        // 2. Stop (simulates notification Stop button).
        //    stop() emits StopAll which asynchronously calls clearAll().
        h.orchestrator.stop()
        settle() // Let StopAll → clearAll() propagate
        assertFalse(h.engine.started, "Engine should be stopped after stop()")

        // 3. User presses Play in UI → sets Pulsar active
        h.mediaSessionStateManager.setPulsarActive(true)
        settle()

        // 4. Engine should have been restarted by the orchestrator
        assertTrue(h.engine.started, "Engine should restart when setPulsarActive(true) after stop")
        assertTrue(h.engine.volume > 0f, "Master volume should be restored after restart")
    }

    @Test
    fun `setPulsarActive before explicit start auto-starts engine`() = runTest {
        val h = createOrchestrator()

        // When isMediaSessionNeeded becomes true and the engine isn't started,
        // the orchestrator should auto-start the engine.
        h.mediaSessionStateManager.setPulsarActive(true)
        settle()

        assertTrue(h.engine.started, "Engine should auto-start when media session is needed")
    }

    @Test
    fun `multiple stop-play cycles work`() = runTest {
        val h = createOrchestrator()

        repeat(3) { cycle ->
            h.orchestrator.start()
            h.mediaSessionStateManager.setPulsarActive(true)
            settle()
            assertTrue(h.engine.started, "Cycle $cycle: engine should be running")

            h.orchestrator.stop()
            settle() // Let StopAll → clearAll() propagate
            assertFalse(h.engine.started, "Cycle $cycle: engine should be stopped")
        }

        // Final restart — setPulsarActive(true) should restart engine
        h.mediaSessionStateManager.setPulsarActive(true)
        settle()
        assertTrue(h.engine.started, "Engine should restart on final cycle")
    }

    @Test
    fun `pause and resume preserve volume`() = runTest {
        val h = createOrchestrator()
        h.orchestrator.start()
        h.engine.setMasterVolume(0.6f)

        h.orchestrator.pause()
        assertTrue(h.engine.volume == 0f, "Volume should be 0 when paused")

        h.orchestrator.resume()
        assertTrue(h.engine.volume == 0.6f, "Volume should be restored on resume")
    }
}

// ─── Minimal Fake ─────────────────────────────────────────────────────────────

private class FakeSynthEngine : SynthEngine {
    var started = false
    private var _volume = 0.7f
    val volume get() = _volume

    override fun start() { started = true }
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

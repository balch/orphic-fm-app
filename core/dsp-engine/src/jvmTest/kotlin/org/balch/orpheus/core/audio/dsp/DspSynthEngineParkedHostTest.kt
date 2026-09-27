package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A parked host reports not-running while monitoring stays active, so the viz
 * polls must gate on monitoring rather than on [AudioEngine.isRunning]. Parking
 * itself must also defer to TTS, which is audible even while paused.
 *
 * ./gradlew :core:dsp-engine:jvmTest --tests '*DspSynthEngineParkedHostTest*'
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DspSynthEngineParkedHostTest {

    @Test
    fun `a viz enabled while the host reports not running still launches once monitoring is active`() = runTest {
        val bridge = ParkedBridge()
        val scope = AppCoroutineScope(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        val engine = buildEngine(bridge, scope)
        try {
            engine.start()
            runCurrent()
            assertEquals(0, bridge.signalVizCalls, "sanity: no Signal Monitor poll before it is enabled")
            assertEquals(0, bridge.spectrumCalls, "sanity: no Spectrograph poll before it is enabled")

            engine.setVizEnabled(true)
            engine.setSpectrumEnabled(true)
            runCurrent()

            assertTrue(bridge.signalVizCalls > 0, "the Signal Monitor poll must launch while the host is parked")
            assertTrue(bridge.spectrumCalls > 0, "the Spectrograph poll must launch while the host is parked")
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `a viz enabled before monitoring starts still waits for it`() = runTest {
        val bridge = ParkedBridge()
        val scope = AppCoroutineScope(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        val engine = buildEngine(bridge, scope)
        try {
            engine.setVizEnabled(true)
            engine.setSpectrumEnabled(true)
            runCurrent()
            assertEquals(0, bridge.signalVizCalls, "no Signal Monitor poll before monitoring starts")
            assertEquals(0, bridge.spectrumCalls, "no Spectrograph poll before monitoring starts")

            engine.start()
            runCurrent()
            assertTrue(bridge.signalVizCalls > 0, "start() honours the earlier Signal Monitor request")
            assertTrue(bridge.spectrumCalls > 0, "start() honours the earlier Spectrograph request")
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `a park never lands while TTS is speaking`() = runTest {
        val bridge = ParkedBridge()
        val scope = AppCoroutineScope(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        val engine = buildEngine(bridge, scope)
        try {
            var wanted = true
            engine.suspendHost { wanted }
            val check = bridge.parkCheck ?: error("suspendHost must reach the platform host")

            assertTrue(check(), "paused, silent and backgrounded: park")
            bridge.ttsPlaying = true
            assertFalse(check(), "TTS bypasses the master mute, so a speaking app must not park")
            bridge.ttsPlaying = false
            wanted = false
            assertFalse(check(), "the caller's own veto still wins")

            engine.resumeHostIfIdle()
            assertEquals(1, bridge.resumes, "resumeHostIfIdle forwards to the platform host")
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    private fun TestScope.buildEngine(bridge: ParkedBridge, scope: AppCoroutineScope): DspSynthEngine {
        val pluginProvider = DspPluginProvider(
            setOf(
                VoicePlugin(bridge),
                FluxPlugin(bridge),
                StereoPlugin(bridge),
                ResonatorPlugin(bridge),
                DrumPlugin(bridge),
                TtsPlugin(bridge),
            ),
        )
        return DspSynthEngine(
            audioEngine = bridge,
            pluginProvider = pluginProvider,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            globalTempo = GlobalTempo(bridge),
            voiceManager = DspVoiceManager(pluginProvider),
            synthController = SynthController(),
            wiringGraphProvider = WiringGraphProvider { ByteArray(0) },
            appCoroutineScope = scope,
        )
    }

    private class TestDispatcherProvider(d: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = d
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
        override val unconfined: CoroutineDispatcher = d
    }

    /** A host that never reports running, as a parked iOS host does. */
    private class ParkedBridge : AudioEngine, NativeDspBridge {
        var signalVizCalls = 0
            private set
        var spectrumCalls = 0
            private set
        var ttsPlaying = false
        var parkCheck: (() -> Boolean)? = null
            private set
        var resumes = 0
            private set

        override fun suspendHost(stillWanted: () -> Boolean) {
            parkCheck = stillWanted
        }

        override fun resumeHostIfIdle() {
            resumes++
        }

        override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int {
            // Channel 0 (LFO) is read only by the Signal Monitor poll.
            if (channel == 0) signalVizCalls++
            return 0
        }

        override fun nativeGetSpectrum(bands: FloatArray): Int {
            spectrumCalls++
            return 0
        }

        // AudioEngine
        override fun start() {}
        override fun stop() {}
        override val isRunning: Boolean = false
        override val sampleRate: Int = 48000
        override fun getCpuLoad(): Float = 0f
        override fun getCurrentTime(): Double = 0.0

        // NativeDspBridge, inert
        override fun nativeSetVoiceGate(index: Int, active: Boolean) {}
        override fun nativeSetVoiceTune(index: Int, tune: Float) {}
        override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) {}
        override fun nativeSetVoiceHarmonics(index: Int, value: Float) {}
        override fun nativeSetVoiceTimbre(index: Int, value: Float) {}
        override fun nativeSetVoiceMorph(index: Int, value: Float) {}
        override fun nativeSetVoiceDecay(index: Int, value: Float) {}
        override fun nativeSetVoiceActive(index: Int, active: Boolean) {}
        override fun nativeSetVoiceHold(index: Int, level: Float) {}
        override fun nativeSetMasterVolume(value: Float) {}
        override fun nativeMasterFade(target: Float, samples: Int, curve: Int) {}
        override fun nativeMasterTapeStop(samples: Int) {}
        override fun nativeMasterScratch(samples: Int) {}
        override fun nativeMasterFilter(samples: Int) {}
        override fun nativeMasterVolumeNow(): Float = 0f
        override fun nativeSetDrive(value: Float) {}
        override fun nativeSetDelayMix(value: Float) {}
        override fun nativeSetVibrato(value: Float) {}
        override fun nativeSetVibratoRate(value: Float) {}
        override fun nativeSetBend(value: Float) {}
        override fun nativeSetPort(uri: String, symbol: String, value: Float) {}
        override fun nativeGetPort(uri: String, symbol: String): Float = 0f
        override fun nativeGetMonitor(out: FloatArray) {}
        override fun nativeGetScope(out: FloatArray, windowMs: Float): Int = -1
        override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {}
        override fun nativeTriggerDrum(drumIndex: Int, accent: Float) {}
        override fun nativeLoadGraph(data: ByteArray): Int = 0
        override fun nativeSetAutomation(
            target: Int,
            voiceIndex: Int,
            times: FloatArray,
            values: FloatArray,
            count: Int,
        ) {}
        override fun nativeClearAutomation(target: Int, voiceIndex: Int) {}
        override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) {}
        override fun nativePlayTts() {}
        override fun nativeStopTts() {}
        override fun nativeIsTtsPlaying(): Int = if (ttsPlaying) 1 else 0
        override fun nativeLoadPulsarClip(slot: Int, samples: FloatArray, sampleRate: Int) {}
        override fun nativeGetPulsarViz(
            gatesOut: BooleanArray,
            velocitiesOut: FloatArray,
            playheadsOut: IntArray,
            stepCountsOut: IntArray,
        ) {}
        override fun nativeGetPulsarActiveEngines(out: IntArray) { out.fill(-1) }
        override fun nativeGetPulsarArrangement(out: IntArray) { out[0] = -1 }
    }
}

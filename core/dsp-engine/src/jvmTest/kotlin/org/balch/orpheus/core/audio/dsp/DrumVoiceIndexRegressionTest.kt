package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * Regression guard for the drum-voice-index bug the kNumMainVoices raise surfaced:
 * SynthEngineRouting.setDrumTriggerSource and DspSynthEngine.syncNativeBridgeState each
 * independently compute "which voice_params slot is this drum" and both used to hardcode
 * `12`. Once kNumMainVoices moved, both stamped active=true on the old (unwired) slot
 * while the real drum sat inactive at DRUM_VOICE_START -- an externally-triggered drum on
 * a fresh session never fired, and no executed test could see it (the C++ suite calls the
 * C API directly; the two existing Kotlin fakes stub nativeSetVoiceActive as a no-op).
 *
 * Pins the index each site actually hands to nativeSetVoiceActive for a drum with an
 * external trigger source to the shared DRUM_VOICE_START constant, not a copy of it.
 *
 * Also covers the same-class bug found while building this fixture: syncNativeBridgeState's
 * stale-gate reset loop hardcoded `0 until 15` (the old kNumVoices = 12 main + 3 drums).
 * With kNumMainVoices raised, drums moved to 24-26 and the score bank extends to slot 23 --
 * `15` silently stopped covering any of them.
 */
class DrumVoiceIndexRegressionTest {

    @Test
    fun `routing activates the drum at DRUM_VOICE_START, not a hardcoded index`() {
        val bridge = RecordingBridge()
        val engine = buildEngine(bridge)
        bridge.voiceActiveCalls.clear() // drop the constructor's initial resync

        engine.routing.setDrumTriggerSource(drumIndex = 1, sourceIndex = 2) // 2 = external

        assertTrue(
            bridge.voiceActiveCalls.any { it.first == DRUM_VOICE_START + 1 && it.second },
            "setDrumTriggerSource must activate voice ${DRUM_VOICE_START + 1}, saw " +
                bridge.voiceActiveCalls,
        )
    }

    @Test
    fun `native resync re-activates the drum at DRUM_VOICE_START, not a hardcoded index`() {
        val bridge = RecordingBridge()
        val engine = buildEngine(bridge)
        engine.routing.setDrumTriggerSource(drumIndex = 2, sourceIndex = 3) // 3 = external
        bridge.voiceActiveCalls.clear() // isolate the resync call below

        engine.syncToNative()

        assertTrue(
            bridge.voiceActiveCalls.any { it.first == DRUM_VOICE_START + 2 && it.second },
            "syncNativeBridgeState must re-activate voice ${DRUM_VOICE_START + 2}, saw " +
                bridge.voiceActiveCalls,
        )
    }

    @Test
    fun `native resync resets gates across the full voice range, not the old 15-slot bound`() {
        val bridge = RecordingBridge()
        val engine = buildEngine(bridge)
        bridge.voiceGateCalls.clear() // isolate the resync call below

        engine.syncToNative()

        val resetIndices = bridge.voiceGateCalls.filter { !it.second }.map { it.first }.toSet()
        val topScoreSlot = NUM_VOICES - NUM_DRUM_VOICES - 1 // 23: last of the 24 score slots
        assertTrue(
            topScoreSlot in resetIndices,
            "gate reset must cover the top score slot ($topScoreSlot), saw $resetIndices",
        )
        for (d in 0 until NUM_DRUM_VOICES) {
            assertTrue(
                DRUM_VOICE_START + d in resetIndices,
                "gate reset must cover drum voice ${DRUM_VOICE_START + d}, saw $resetIndices",
            )
        }
    }

    private fun buildEngine(bridge: RecordingBridge): DspSynthEngine {
        val pluginProvider = DspPluginProvider(
            setOf(
                VoicePlugin(bridge),
                FluxPlugin(bridge),
                StereoPlugin(bridge),
                ResonatorPlugin(bridge),
                DrumPlugin(bridge),
            ),
        )
        val dispatcherProvider = ImmediateDispatcherProvider()
        return DspSynthEngine(
            audioEngine = bridge,
            pluginProvider = pluginProvider,
            dispatcherProvider = dispatcherProvider,
            globalTempo = GlobalTempo(bridge),
            voiceManager = DspVoiceManager(pluginProvider),
            synthController = SynthController(),
            wiringGraphProvider = WiringGraphProvider { ByteArray(0) },
            appCoroutineScope = AppCoroutineScope(dispatcherProvider),
        )
    }

    private class ImmediateDispatcherProvider : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }

    /**
     * Fake AudioEngine + NativeDspBridge (DspSynthEngine casts one to the other) that
     * records every nativeSetVoiceActive / nativeSetVoiceGate call. Everything else is an
     * inert stub, matching the established CountingBridge pattern in
     * SynthEngineMonitorTurntableGateTest.
     */
    private class RecordingBridge : AudioEngine, NativeDspBridge {
        val voiceActiveCalls = mutableListOf<Pair<Int, Boolean>>()
        val voiceGateCalls = mutableListOf<Pair<Int, Boolean>>()

        override fun nativeSetVoiceActive(index: Int, active: Boolean) {
            voiceActiveCalls.add(index to active)
        }

        // AudioEngine
        override fun start() {}
        override fun stop() {}
        override val isRunning: Boolean = false
        override val sampleRate: Int = 48000
        override fun getCpuLoad(): Float = 0f
        override fun getCurrentTime(): Double = 0.0

        // NativeDspBridge — inert stubs, except the two calls this suite pins
        override fun nativeSetVoiceGate(index: Int, active: Boolean) {
            voiceGateCalls.add(index to active)
        }
        override fun nativeSetVoiceTune(index: Int, tune: Float) {}
        override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) {}
        override fun nativeSetVoiceHarmonics(index: Int, value: Float) {}
        override fun nativeSetVoiceTimbre(index: Int, value: Float) {}
        override fun nativeSetVoiceMorph(index: Int, value: Float) {}
        override fun nativeSetVoiceDecay(index: Int, value: Float) {}
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
        override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int = 0
        override fun nativeGetSpectrum(bands: FloatArray): Int = 0
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
        override fun nativeIsTtsPlaying(): Int = 0
        override fun nativeGetPulsarViz(
            gatesOut: BooleanArray,
            velocitiesOut: FloatArray,
            playheadsOut: IntArray,
            stepCountsOut: IntArray,
        ) {}
        override fun nativeGetPulsarActiveEngines(out: IntArray) {}
        override fun nativeGetPulsarArrangement(out: IntArray) {}
    }
}

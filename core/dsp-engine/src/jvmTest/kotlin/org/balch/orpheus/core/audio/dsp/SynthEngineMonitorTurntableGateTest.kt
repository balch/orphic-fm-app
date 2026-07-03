package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the turntable-viz visibility gate (review finding #2).
 *
 * Turntable viz polling must be gated on `monitoringActive`, exactly like the
 * monitor / Pulsar / signal-scope polls. The bug: `setUiVisible(true)` launched
 * the turntable poll *outside* the `monitoringActive` block whenever
 * `turntableVizRequested` was still set, so a background -> foreground toggle
 * after `stopMonitoring()` would poll a stopped engine.
 *
 * Determinism: a [StandardTestDispatcher] drives every poll, and [runCurrent]
 * runs each launched coroutine up to its first `delay` (one iteration) without
 * advancing virtual time — so an active poll is observable as a bump in
 * [CountingBridge.turntableVizCalls] while the infinite poll loops never hang
 * the test (which `advanceUntilIdle` would).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynthEngineMonitorTurntableGateTest {

    @Test
    fun `turntable poll does not relaunch on foreground after stopMonitoring`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = CountingBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            // Engine running + turntable requested -> poll runs.
            monitor.startMonitoring()
            monitor.startTurntableViz()
            runCurrent()
            assertTrue(
                bridge.turntableVizCalls > 0,
                "sanity: turntable poll should run while monitoring + foreground",
            )

            // Engine stops: monitoringActive = false, all polls cancelled.
            monitor.stopMonitoring()
            runCurrent()
            val callsAfterStop = bridge.turntableVizCalls

            // Background -> foreground toggle while the engine is stopped.
            monitor.setUiVisible(false)
            monitor.setUiVisible(true)
            runCurrent()

            assertEquals(
                callsAfterStop, bridge.turntableVizCalls,
                "setUiVisible(true) must NOT relaunch the turntable poll after " +
                    "stopMonitoring() — monitoringActive is false, so polling a stopped " +
                    "engine would be wrong.",
            )
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `turntable poll resumes on foreground while monitoring stays active`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = CountingBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            monitor.startMonitoring()
            monitor.startTurntableViz()
            runCurrent()
            assertTrue(bridge.turntableVizCalls > 0, "sanity: turntable poll running")

            // Background — poll pauses (monitoringActive stays true).
            monitor.setUiVisible(false)
            runCurrent()
            val pausedCalls = bridge.turntableVizCalls

            // Foreground — still monitoring, so the poll must come back.
            monitor.setUiVisible(true)
            runCurrent()
            assertTrue(
                bridge.turntableVizCalls > pausedCalls,
                "turntable poll must resume on foreground while the engine is still " +
                    "monitoring — the gate must not over-restrict the normal resume path.",
            )
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class TestDispatcherProvider(d: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = d
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
        override val unconfined: CoroutineDispatcher = d
    }

    /**
     * Counts [nativeGetTurntableViz] calls — the observable side effect of an
     * active turntable poll. Every other bridge method is an inert stub; the
     * remaining poll methods return empty/sentinel data so the monitor's other
     * loops run without touching real native state.
     */
    private class CountingBridge : NativeDspBridge {
        var turntableVizCalls = 0
            private set

        override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {
            turntableVizCalls++
        }

        // Poll surface the monitor exercises — kept inert.
        override fun nativeGetMonitor(out: FloatArray) {}
        override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int = 0
        override fun nativeGetPulsarViz(
            gatesOut: BooleanArray,
            velocitiesOut: FloatArray,
            playheadsOut: IntArray,
            stepCountsOut: IntArray,
        ) {}
        override fun nativeGetPulsarActiveEngines(out: IntArray) { for (i in out.indices) out[i] = -1 }
        override fun nativeGetPulsarArrangement(out: IntArray) { out[0] = -1 }

        // Unused control surface.
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
    }
}

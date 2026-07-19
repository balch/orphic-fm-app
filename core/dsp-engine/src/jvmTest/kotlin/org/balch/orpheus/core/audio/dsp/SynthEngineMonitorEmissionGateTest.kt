package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression guards for the 60fps viz emission gating (review follow-up).
 *
 * - [SynthEngineMonitor.pulsarVizFlow] must not re-emit [PulsarVizData] when
 *   consecutive bridge ticks return identical data (an idle grid is zero
 *   allocations, zero recompositions), but must emit the moment a playhead
 *   moves — an over-aggressive gate would freeze the playhead on screen.
 * - The turntable deck flows seed one sized silent frame after a reset (so
 *   consumers can size their canvases), skip steady-silence repeats, and emit
 *   exactly once on a play -> silence transition. FloatArray compares by
 *   reference, so before the gate every 16ms `copyOf()` was a fresh emission
 *   even when nothing changed.
 *
 * Determinism follows [SynthEngineMonitorTurntableGateTest]: a
 * [StandardTestDispatcher] drives the polls, [runCurrent] runs a freshly
 * launched poll up to its first `delay`, and each [tick] (advanceTimeBy 16ms +
 * runCurrent) executes exactly one further poll iteration — 16ms matches the
 * monitor's VIZ_POLL_INTERVAL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynthEngineMonitorEmissionGateTest {

    @Test
    fun `pulsar viz skips identical ticks and emits on playhead change`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScriptedBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            val emissions = mutableListOf<PulsarVizData>()
            backgroundScope.launch { monitor.pulsarVizFlow.collect { emissions.add(it) } }
            runCurrent()
            assertEquals(1, emissions.size, "sanity: collector sees the flow's initial value")

            monitor.startMonitoring()
            runCurrent()
            assertEquals(
                2, emissions.size,
                "first tick must emit — polled playheads (0) differ from the default (-1)",
            )

            repeat(3) { tick() }
            assertEquals(
                2, emissions.size,
                "identical bridge data across consecutive ticks must not re-emit PulsarVizData",
            )

            bridge.playhead0 = 5
            tick()
            assertEquals(3, emissions.size, "a playhead change must emit a new PulsarVizData")
            assertEquals(5, emissions.last().playheads[0], "emitted frame carries the new playhead")

            tick()
            assertEquals(
                3, emissions.size,
                "the changed frame must not re-emit while the data stays identical again",
            )
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `turntable viz seeds sized silent buffer once and gates steady silence`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScriptedBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            monitor.startMonitoring()
            monitor.startTurntableViz()
            assertEquals(0, monitor.djVizFlowA.value.size, "sanity: flow starts in the reset state")

            runCurrent()
            val seeded = monitor.djVizFlowA.value
            assertTrue(
                seeded.isNotEmpty(),
                "first poll after reset must seed a sized buffer even in silence",
            )
            assertEquals(
                bridge.turntableBufSize, seeded.size,
                "seed matches the native buffer size (128 waveform + 1 playhead)",
            )
            assertTrue(seeded.all { it == 0f }, "seeded buffer is silent")

            repeat(3) { tick() }
            assertSame(
                seeded, monitor.djVizFlowA.value,
                "steady silence must not re-emit — the flow keeps the seeded instance",
            )

            // Reset -> re-seed: stopTurntableViz clears the flow to FloatArray(0).
            monitor.stopTurntableViz()
            assertEquals(0, monitor.djVizFlowA.value.size, "stop resets the flow to FloatArray(0)")
            monitor.startTurntableViz()
            runCurrent()
            assertEquals(
                bridge.turntableBufSize, monitor.djVizFlowA.value.size,
                "first poll after the reset must seed a sized buffer again",
            )
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `turntable viz emits exactly once on play to silence transition`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScriptedBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            monitor.startMonitoring()
            monitor.startTurntableViz()
            runCurrent()
            assertTrue(monitor.djVizFlowA.value.isNotEmpty(), "sanity: silent seed emitted")

            // Deck starts playing: the non-silent frame must flow through.
            val playFrame = FloatArray(bridge.turntableBufSize) { 0.25f }
            bridge.turntableFrame = playFrame
            tick()
            val playing = monitor.djVizFlowA.value
            assertTrue(playing.contentEquals(playFrame), "playing frame is emitted")

            // Deck goes silent: exactly one silent emission, then the gate holds.
            bridge.turntableFrame = null
            tick()
            val silent = monitor.djVizFlowA.value
            assertNotSame(playing, silent, "play -> silence transition must emit once")
            assertEquals(playing.size, silent.size, "transition frame keeps the viz size")
            assertTrue(silent.all { it == 0f }, "transition frame is silent")

            repeat(3) { tick() }
            assertSame(
                silent, monitor.djVizFlowA.value,
                "silence after the transition must not re-emit",
            )
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    /** Runs exactly one further 16ms poll tick (the monitor's VIZ_POLL_INTERVAL). */
    private fun TestScope.tick() {
        advanceTimeBy(16.milliseconds)
        runCurrent()
    }

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class TestDispatcherProvider(d: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = d
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
        override val unconfined: CoroutineDispatcher = d
    }

    /**
     * Bridge whose poll outputs the test scripts: [turntableFrame] is written
     * into every turntable poll (null = silence), [playhead0] is track 0's
     * playhead. Every poll method overwrites its output buffers completely so
     * ticks are deterministic regardless of the monitor's buffer reuse.
     */
    private class ScriptedBridge : NativeDspBridge {
        /** Frame written for both decks by [nativeGetTurntableViz]; null = all zeros. */
        var turntableFrame: FloatArray? = null

        /** Track 0 playhead reported by [nativeGetPulsarViz]. */
        var playhead0 = 0

        /** Size of the buffer the monitor polls turntable viz into (129). */
        var turntableBufSize = 0
            private set

        override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {
            turntableBufSize = outBuf.size
            outBuf.fill(0f)
            turntableFrame?.copyInto(outBuf)
        }

        override fun nativeGetPulsarViz(
            gatesOut: BooleanArray,
            velocitiesOut: FloatArray,
            playheadsOut: IntArray,
            stepCountsOut: IntArray,
        ) {
            gatesOut.fill(false)
            velocitiesOut.fill(0f)
            playheadsOut.fill(0)
            playheadsOut[0] = playhead0
            stepCountsOut.fill(16)
        }

        override fun nativeGetPulsarActiveEngines(out: IntArray) {
            out.fill(-1)
        }

        override fun nativeGetPulsarArrangement(out: IntArray) {
            out[0] = -1
        }

        // Poll surface the monitor exercises — kept inert.
        override fun nativeGetMonitor(out: FloatArray) {}
        override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int = 0
        override fun nativeGetSpectrum(bands: FloatArray): Int = 0

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

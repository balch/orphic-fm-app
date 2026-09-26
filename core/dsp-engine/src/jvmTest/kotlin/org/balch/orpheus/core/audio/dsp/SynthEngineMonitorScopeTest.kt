package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The master-mix scope poll: it fills one preallocated array in place and allocates nothing per
 * tick, publishes by version, and runs only behind its own gate while monitoring with the UI
 * visible. Determinism follows [SynthEngineMonitorEmissionGateTest]: [runCurrent] runs a fresh
 * poll to its first delay and each [tick] runs exactly one more iteration.
 *
 * ./gradlew :core:dsp-engine:jvmTest --tests '*SynthEngineMonitorScopeTest*'
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynthEngineMonitorScopeTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun `a scope tick allocates nothing`() {
        val provider = TestDispatcherProvider(StandardTestDispatcher())
        val scope = AppCoroutineScope(provider)
        val bridge = ScopeBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        val scratch = FloatArray(SynthEngineMonitor.SCOPE_POINTS)
        try {
            // Warm the JIT on the tick path first.
            repeat(20_000) { monitor.pollScope(scratch) }
            val ticks = 10_000
            val start = threads.currentThreadAllocatedBytes
            repeat(ticks) { monitor.pollScope(scratch) }
            val allocated = threads.currentThreadAllocatedBytes - start
            println("[scope] $allocated B over $ticks ticks")
            // One object per tick would be at least 16 B x 10k ticks.
            assertTrue(allocated < 1_024, "the scope tick allocated $allocated B over $ticks ticks")
            assertEquals(0, bridge.arraySwitches, "every tick must fill the scratch it was given")
            assertEquals(20_000 + ticks, monitor.scopeFrame.version, "every good read publishes")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the poll publishes each window by version into the same array`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScopeBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        val frame = monitor.scopeFrame
        val dest = FloatArray(frame.size)
        try {
            assertEquals(SynthEngineMonitor.SCOPE_POINTS, frame.size)
            assertEquals(SynthEngineMonitor.SCOPE_WINDOW_MS, frame.windowMs)
            assertEquals(0, frame.version, "sanity: nothing published yet")

            monitor.startMonitoring()
            monitor.setScopeEnabled(true)
            runCurrent()
            assertEquals(1, frame.version, "the first tick publishes")
            assertEquals(SynthEngineMonitor.SCOPE_WINDOW_MS, bridge.lastWindowMs, "the window reaches the bridge")

            repeat(4) { tick() }
            assertEquals(5, frame.version, "one publish per tick")
            assertEquals(0, bridge.arraySwitches, "the poll hands the bridge one array for its lifetime")

            // The frame copies out the bridge's last window and its trigger flag.
            bridge.result = 0
            tick()
            assertFalse(frame.readInto(dest), "an untriggered read publishes as untriggered")
            assertEquals(bridge.calls.toFloat(), dest[0], "the latest window is the one read")
            assertEquals(bridge.calls + 0.5f, dest[dest.size - 1])
            bridge.result = 1
            tick()
            assertTrue(frame.readInto(dest), "a triggered read publishes as triggered")

            // A lapped read (-1) keeps the last window.
            bridge.result = -1
            val before = frame.version
            tick()
            assertEquals(before, frame.version, "a failed read must not publish")
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `a relaunched poll fills its own scratch`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScopeBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            monitor.startMonitoring()
            monitor.setScopeEnabled(true)
            runCurrent()
            val first = bridge.lastArray

            // Off then on inside one tick: cancel() is cooperative, so on a real dispatcher the
            // old job may still be mid-read. It must not share an array with the new one.
            monitor.setScopeEnabled(false)
            monitor.setScopeEnabled(true)
            runCurrent()
            assertEquals(1, bridge.arraySwitches, "the relaunched job reads into a new array")
            assertTrue(first !== bridge.lastArray, "the old job's scratch is not reused")
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `the poll runs behind its own gate`() = runTest {
        val provider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val scope = AppCoroutineScope(provider)
        val bridge = ScopeBridge()
        val monitor = SynthEngineMonitor(bridge, provider, scope)
        try {
            // Other viz gates don't start it.
            monitor.startMonitoring()
            monitor.setVizEnabled(enabled = true, isRunning = true)
            monitor.setSpectrumEnabled(enabled = true, isRunning = true)
            runCurrent()
            assertEquals(0, bridge.calls, "the Signal Monitor and Spectrograph gates must not run the scope")
            monitor.setVizEnabled(enabled = false, isRunning = true)
            monitor.setSpectrumEnabled(enabled = false, isRunning = true)

            // Requested before the engine runs: waits for startMonitoring.
            monitor.stopMonitoring()
            monitor.setScopeEnabled(true)
            runCurrent()
            assertEquals(0, bridge.calls, "no scope poll while the engine is stopped")
            monitor.startMonitoring()
            runCurrent()
            assertEquals(1, bridge.calls, "startMonitoring honours an earlier request")

            // Backgrounded: paused; foreground: back.
            monitor.setUiVisible(false)
            tick()
            assertEquals(1, bridge.calls, "no scope poll while the UI is hidden")
            monitor.setUiVisible(true)
            runCurrent()
            assertEquals(2, bridge.calls, "the scope poll resumes on foreground")

            // Disabled: stops, and the last window stays readable.
            val held = monitor.scopeFrame.version
            monitor.setScopeEnabled(false)
            repeat(3) { tick() }
            assertEquals(2, bridge.calls, "a disabled scope does not poll")
            assertEquals(held, monitor.scopeFrame.version, "disabling holds the last window")
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
     * Writes a window stamped with the call count (point i = calls + i / (size - 1) * 0.5) and
     * returns [result]. Counts, by identity and without allocating, how often a call's array
     * differs from the previous call's.
     */
    private class ScopeBridge : NativeDspBridge {
        var result = 1
        var calls = 0
            private set
        var lastWindowMs = 0f
            private set
        var lastArray: FloatArray? = null
            private set
        /** Calls whose array was not the previous call's; the first call doesn't count. */
        var arraySwitches = 0
            private set

        override fun nativeGetScope(out: FloatArray, windowMs: Float): Int {
            calls++
            lastWindowMs = windowMs
            if (out !== lastArray) {
                if (lastArray != null) arraySwitches++
                lastArray = out
            }
            if (result >= 0) {
                val last = out.size - 1
                for (i in out.indices) out[i] = calls + 0.5f * i / last
            }
            return result
        }

        // Poll surface the monitor exercises — kept inert.
        override fun nativeGetMonitor(out: FloatArray) {}
        override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int = 0
        override fun nativeGetSpectrum(bands: FloatArray): Int = 0
        override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {}
        override fun nativeGetPulsarViz(
            gatesOut: BooleanArray,
            velocitiesOut: FloatArray,
            playheadsOut: IntArray,
            stepCountsOut: IntArray,
        ) {}
        override fun nativeGetPulsarActiveEngines(out: IntArray) { out.fill(-1) }
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
        override fun nativeLoadPulsarClip(slot: Int, samples: FloatArray, sampleRate: Int) {}
    }
}

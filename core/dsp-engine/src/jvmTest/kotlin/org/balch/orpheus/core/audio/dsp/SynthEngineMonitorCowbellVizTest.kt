package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cowbell viz channel: the loudest sample since the last poll, clamped to 0..1 with NaN
 * skipped. Its poll runs only while the cowbell panel requests it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynthEngineMonitorCowbellVizTest {

    /** `VIZ_COWBELL_OUT` in `orpheus_viz.h`. */
    private val cowbellChannel = 42

    private fun provider(scheduler: TestCoroutineScheduler? = null) = object : DispatcherProvider {
        private val d: CoroutineDispatcher = StandardTestDispatcher(scheduler)
        override val main = d
        override val io = d
        override val default = d
        override val unconfined = d
    }

    private fun bridgeReading(samples: FloatArray, channels: MutableList<Int>): NativeDspBridge =
        Proxy.newProxyInstance(
            NativeDspBridge::class.java.classLoader,
            arrayOf(NativeDspBridge::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "nativeGetViz" -> {
                    channels += args[0] as Int
                    val out = args[1] as FloatArray
                    samples.copyInto(out)
                    samples.size
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "CowbellVizBridge"
                else -> when (method.returnType) {
                    Int::class.javaPrimitiveType -> 0
                    Float::class.javaPrimitiveType -> 0f
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        } as NativeDspBridge

    private fun publishedFor(vararg samples: Float): Float {
        val channels = mutableListOf<Int>()
        val p = provider()
        val monitor = SynthEngineMonitor(bridgeReading(samples, channels), p, AppCoroutineScope(p))
        monitor.pollCowbellOut(IntArray(64), FloatArray(480), IntArray(1))
        assertEquals(listOf(cowbellChannel), channels, "the cowbell peak is viz channel $cowbellChannel")
        return monitor.cowbellOutVizFlow.value
    }

    @Test
    fun loudestSampleWins() = assertEquals(0.9f, publishedFor(0.1f, 0.9f, 0.5f))

    @Test
    fun overOneClampsToOne() = assertEquals(1f, publishedFor(3f))

    @Test
    fun negativeClampsToZero() = assertEquals(0f, publishedFor(-0.2f))

    @Test
    fun nanSamplesAreSkipped() = assertEquals(0.8f, publishedFor(0.8f, Float.NaN))

    @Test
    fun allNanPublishesZero() = assertEquals(0f, publishedFor(Float.NaN))

    @Test
    fun pollRunsOnlyWhileRequested() = runTest {
        val p = provider(testScheduler)
        val scope = AppCoroutineScope(p)
        val channels = mutableListOf<Int>()
        val monitor = SynthEngineMonitor(bridgeReading(floatArrayOf(0.6f), channels), p, scope)
        fun cowbellPolls() = channels.count { it == cowbellChannel }
        try {
            // Requested before the engine runs: it starts with monitoring.
            monitor.startCowbellViz()
            runCurrent()
            assertEquals(0, cowbellPolls(), "no poll before monitoring")
            monitor.startMonitoring()
            runCurrent()
            assertTrue(cowbellPolls() > 0, "the request starts the poll with monitoring")
            assertEquals(0.6f, monitor.cowbellOutVizFlow.value)

            // The panel leaves: the poll stops and the glow drops.
            monitor.stopCowbellViz()
            runCurrent()
            val stopped = cowbellPolls()
            testScheduler.advanceTimeBy(200)
            runCurrent()
            assertEquals(stopped, cowbellPolls(), "the poll keeps running after the request ended")
            assertEquals(0f, monitor.cowbellOutVizFlow.value)

            // The Signal Monitor alone does not feed it.
            monitor.setVizEnabled(true)
            runCurrent()
            assertEquals(stopped, cowbellPolls(), "the Signal Monitor poll reads the cowbell channel")

            // Back again, and through a background pause.
            monitor.startCowbellViz()
            runCurrent()
            assertTrue(cowbellPolls() > stopped)
            monitor.setUiVisible(false)
            runCurrent()
            val paused = cowbellPolls()
            testScheduler.advanceTimeBy(200)
            runCurrent()
            assertEquals(paused, cowbellPolls(), "the poll runs in the background")
            monitor.setUiVisible(true)
            runCurrent()
            assertTrue(cowbellPolls() > paused, "the poll does not resume in the foreground")
        } finally {
            monitor.stopMonitoring()
            scope.cancel()
            runCurrent()
        }
    }
}

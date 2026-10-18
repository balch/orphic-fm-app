package org.balch.orpheus.features.record

import org.balch.orpheus.core.controller.MidiNoteEvent
import org.balch.orpheus.features.record.telemetry.GateOn
import org.balch.orpheus.features.record.telemetry.TakeEventRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TakeEventRecorderConcurrencyTest {

    @Test
    fun concurrentWritersWithStopLoseNothingAndDoNotThrow() {
        val recorder = TakeEventRecorder { System.nanoTime() / 1_000_000 }
        val writers = 4
        val perWriter = 5_000
        val pool = Executors.newFixedThreadPool(writers + 1)
        val ready = CountDownLatch(writers)
        val go = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        recorder.start()
        repeat(writers) { w ->
            pool.execute {
                try {
                    ready.countDown()
                    go.await()
                    repeat(perWriter) { i ->
                        recorder.gateOn(w, i.toFloat())
                        recorder.control("org.balch.orpheus.plugins.voice:tune_$w", i / 1000f)
                        recorder.midiNote(MidiNoteEvent(60 + w, 100, true, w))
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        var result = emptyList<org.balch.orpheus.features.record.telemetry.TakeEvent>()
        pool.execute {
            try {
                ready.await()
                go.await()
                Thread.sleep(2)
                result = recorder.stop()
            } catch (e: Throwable) {
                errors += e
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertTrue(errors.isEmpty(), "errors: $errors")
        assertFalse(recorder.isActive)
        assertEquals(result.sortedBy { it.t }, result, "stop() result must be time-sorted")
        val gates = result.filterIsInstance<GateOn>()
        assertEquals(gates.size, gates.map { it.voice to it.midiNote }.toSet().size, "no duplicated events")
    }

    @Test
    fun concurrentWritersLoseNoEvents() {
        val recorder = TakeEventRecorder { 0L }
        val writers = 8
        val perWriter = 20_000
        val pool = Executors.newFixedThreadPool(writers)
        val go = CountDownLatch(1)

        recorder.start()
        repeat(writers) { w ->
            pool.execute {
                go.await()
                repeat(perWriter) { i -> recorder.gateOn(w, i.toFloat()) }
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(writers * perWriter, recorder.stop().size)
    }
}

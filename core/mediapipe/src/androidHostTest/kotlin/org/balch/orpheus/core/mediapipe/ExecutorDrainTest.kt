package org.balch.orpheus.core.mediapipe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutorDrainTest {

    @Test
    fun `drain returns only after the task in flight has finished`() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        executor.execute {
            started.countDown()
            Thread.sleep(100)
            finished.set(true)
        }
        started.await()

        assertTrue(executor.drain(timeoutMs = 5_000))
        assertTrue(finished.get(), "drain returned while the task was still running")
    }

    @Test
    fun `drain gives up on a task that outlives the bound and says so`() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Self-releasing, so an unbounded drain fails the assertion instead of hanging the suite.
        executor.execute {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        started.await()

        try {
            assertFalse(executor.drain(timeoutMs = 50))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `drain on an idle or already drained executor returns at once`() {
        val executor = Executors.newSingleThreadExecutor()

        assertTrue(executor.drain(timeoutMs = 5_000))
        assertTrue(executor.drain(timeoutMs = 5_000))
        assertTrue(executor.isShutdown)
    }

    @Test
    fun `an interrupted wait reports not drained and keeps the interrupt`() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        started.await()

        Thread.currentThread().interrupt()
        try {
            assertFalse(executor.drain(timeoutMs = 5_000))
            // interrupted() also clears the flag, so it cannot leak into the next test.
            assertTrue(Thread.interrupted(), "the interrupt was swallowed")
        } finally {
            Thread.interrupted()
            release.countDown()
        }
    }
}

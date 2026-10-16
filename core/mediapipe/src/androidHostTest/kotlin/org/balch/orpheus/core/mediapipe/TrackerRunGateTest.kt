package org.balch.orpheus.core.mediapipe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerRunGateTest {

    private fun foregroundGate() = TrackerRunGate().apply { onForeground(true) }

    @Test
    fun `a wanted tracker pauses in the background and resumes on return`() {
        val gate = foregroundGate()
        assertTrue(gate.onWanted(true))

        assertFalse(gate.onForeground(false))
        assertTrue(gate.onForeground(true))
    }

    @Test
    fun `a tracker the user turned off stays off across a background cycle`() {
        val gate = foregroundGate()
        gate.onWanted(true)
        gate.onWanted(false)

        assertFalse(gate.onForeground(false))
        assertFalse(gate.onForeground(true))
    }

    @Test
    fun `stop while backgrounded is not undone by returning`() {
        val gate = foregroundGate()
        gate.onWanted(true)
        gate.onForeground(false)

        assertFalse(gate.onWanted(false))
        assertFalse(gate.onForeground(true))
    }

    @Test
    fun `start while backgrounded waits for the foreground`() {
        val gate = TrackerRunGate()

        assertFalse(gate.onWanted(true))
        assertTrue(gate.onForeground(true))
    }

    /** An app that pauses its own run on ON_STOP calls stop/start around the same moments. */
    @Test
    fun `app code repeating the lifecycle's calls lands in the same place in either order`() {
        val appFirst = foregroundGate().apply { onWanted(true) }
        assertFalse(appFirst.onWanted(false))
        assertFalse(appFirst.onForeground(false))
        assertFalse(appFirst.onWanted(true))
        assertTrue(appFirst.onForeground(true))

        val lifecycleFirst = foregroundGate().apply { onWanted(true) }
        assertFalse(lifecycleFirst.onForeground(false))
        assertFalse(lifecycleFirst.onWanted(false))
        assertFalse(lifecycleFirst.onForeground(true))
        assertTrue(lifecycleFirst.onWanted(true))
    }

    @Test
    fun `repeated start or stop calls change nothing`() {
        val gate = foregroundGate()

        assertTrue(gate.onWanted(true))
        assertTrue(gate.onWanted(true))
        assertFalse(gate.onWanted(false))
        assertFalse(gate.onWanted(false))
    }
}

package org.balch.orpheus.features.pulsar.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionQueueTest {

    private val writes = mutableListOf<Int>()
    private val queue = SectionQueue { writes += it }

    @Test
    fun `a request writes index plus one and shows as queued`() {
        assertTrue(queue.request(index = 2, currentSection = 0, sectionCount = 4, outroArmed = false))
        assertEquals(listOf(3), writes)
        assertEquals(2, queue.queued.value)
    }

    @Test
    fun `the last request wins`() {
        queue.request(index = 2, currentSection = 0, sectionCount = 4, outroArmed = false)
        queue.request(index = 3, currentSection = 0, sectionCount = 4, outroArmed = false)
        assertEquals(listOf(3, 4), writes)
        assertEquals(3, queue.queued.value)
    }

    @Test
    fun `the playing section, an out of range index and an armed outro are ignored`() {
        assertFalse(queue.request(index = 1, currentSection = 1, sectionCount = 4, outroArmed = false))
        assertFalse(queue.request(index = 4, currentSection = 1, sectionCount = 4, outroArmed = false))
        assertFalse(queue.request(index = -1, currentSection = 1, sectionCount = 4, outroArmed = false))
        assertFalse(queue.request(index = 2, currentSection = 1, sectionCount = 4, outroArmed = true))
        assertTrue(writes.isEmpty())
        assertEquals(SectionQueue.NONE, queue.queued.value)
    }

    @Test
    fun `arriving at the queued section clears it and another section does not`() {
        queue.request(index = 2, currentSection = 0, sectionCount = 4, outroArmed = false)
        queue.onSectionObserved(1)
        assertEquals(2, queue.queued.value)
        queue.onSectionObserved(2)
        assertEquals(SectionQueue.NONE, queue.queued.value)
    }

    @Test
    fun `clear drops the queued section without writing the port`() {
        queue.request(index = 2, currentSection = 0, sectionCount = 4, outroArmed = false)
        queue.clear()
        assertEquals(SectionQueue.NONE, queue.queued.value)
        assertEquals(listOf(3), writes)
    }
}

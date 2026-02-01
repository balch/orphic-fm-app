package org.balch.orpheus.core.tidal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the Tidal Pattern engine.
 */
class PatternTest {
    
    @Test
    fun `pure creates constant pattern`() {
        val pattern = Pattern.pure(42)
        val events = pattern.query(Arc.UNIT)
        
        assertEquals(1, events.size)
        assertEquals(42, events[0].value)
        assertTrue(events[0].hasOnset())
    }
    
    @Test
    fun `fast doubles speed`() {
        val pattern = Pattern.pure("x").fast(2.0)
        val events = pattern.query(Arc.UNIT)
        
        // Should get 2 events in one cycle
        assertEquals(2, events.size)
    }
    
    @Test
    fun `slow halves speed`() {
        val pattern = Pattern.pure("x").slow(2.0)
        
        // Query first cycle - should get partial event
        val events1 = pattern.query(Arc(0.0, 1.0))
        // Query second cycle - should get the remainder
        val events2 = pattern.query(Arc(1.0, 2.0))
        
        // Full event spans 2 cycles when slowed by 2
        assertTrue(events1.isNotEmpty() || events2.isNotEmpty())
    }
    
    @Test
    fun `fastcat distributes patterns evenly`() {
        val pattern = Pattern.fastcat(
            Pattern.pure(1),
            Pattern.pure(2),
            Pattern.pure(3),
            Pattern.pure(4)
        )
        val events = pattern.query(Arc.UNIT)
        
        // Should have 4 events in one cycle
        assertEquals(4, events.size)
        
        // Values should be in order
        val values = events.sortedBy { it.part.start }.map { it.value }
        assertEquals(listOf(1, 2, 3, 4), values)
    }
    
    @Test
    fun `stack layers patterns`() {
        val pattern = Pattern.stack(
            Pattern.pure("a"),
            Pattern.pure("b")
        )
        val events = pattern.query(Arc.UNIT)
        
        // Should have events from both patterns
        assertEquals(2, events.size)
        val values = events.map { it.value }.toSet()
        assertEquals(setOf("a", "b"), values)
    }
    
    @Test
    fun `slowcat cycles through patterns`() {
        val pattern = Pattern.slowcat(
            Pattern.pure("a"),
            Pattern.pure("b"),
            Pattern.pure("c")
        )
        
        // Cycle 0 should have "a"
        val eventsC0 = pattern.query(Arc(0.0, 1.0))
        assertEquals(1, eventsC0.size)
        assertEquals("a", eventsC0[0].value)
        
        // Cycle 1 should have "b"
        val eventsC1 = pattern.query(Arc(1.0, 2.0))
        assertEquals(1, eventsC1.size)
        assertEquals("b", eventsC1[0].value)
        
        // Cycle 2 should have "c"
        val eventsC2 = pattern.query(Arc(2.0, 3.0))
        assertEquals(1, eventsC2.size)
        assertEquals("c", eventsC2[0].value)
    }
    
    @Test
    fun `every applies transformation periodically`() {
        val pattern = Pattern.pure(1).every(2) { it.fmap { v -> v * 10 } }
        
        // Cycle 0 (every 2nd starting at 0) - transformed
        val eventsC0 = pattern.query(Arc(0.0, 1.0))
        assertEquals(10, eventsC0[0].value)
        
        // Cycle 1 - not transformed
        val eventsC1 = pattern.query(Arc(1.0, 2.0))
        assertEquals(1, eventsC1[0].value)
        
        // Cycle 2 - transformed again
        val eventsC2 = pattern.query(Arc(2.0, 3.0))
        assertEquals(10, eventsC2[0].value)
    }
    
    @Test
    fun `infix fast operator works`() {
        val pattern = Pattern.pure("x") fast 4
        val events = pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
    }
    
    @Test
    fun `fmap transforms values`() {
        val pattern = Pattern.pure(5).fmap { it * 2 }
        val events = pattern.query(Arc.UNIT)
        assertEquals(10, events[0].value)
    }
    
    @Test
    fun `silence produces no events`() {
        val pattern = Pattern.silence<Int>()
        val events = pattern.query(Arc.UNIT)
        assertTrue(events.isEmpty())
    }
}

package org.balch.orpheus.core.tidal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for the Tidal mini-notation parser.
 */
class TidalParserTest {
    
    @Test
    fun `parseGates parses single number`() {
        val result = TidalParser.parseGates("1")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        assertIs<TidalEvent.Gate>(events[0].value)
        assertEquals(0, (events[0].value as TidalEvent.Gate).voiceIndex)
    }
    
    @Test
    fun `parseGates parses sequence`() {
        val result = TidalParser.parseGates("1 2 3 4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
        
        val indices = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Gate).voiceIndex }
        assertEquals(listOf(0, 1, 2, 3), indices)
    }
    
    @Test
    fun `parseGates handles repetition`() {
        val result = TidalParser.parseGates("1*4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
        
        // All should be voice 0
        events.forEach { event ->
            assertEquals(0, (event.value as TidalEvent.Gate).voiceIndex)
        }
    }
    
    @Test
    fun `parseGates handles grouping`() {
        val result = TidalParser.parseGates("[1 2] 3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        // [1 2] in first half, 3 in second half = 3 events
        assertEquals(3, events.size)
    }
    
    @Test
    fun `parseGates handles silence`() {
        val result = TidalParser.parseGates("1 ~ 3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        // 1 and 3, but not the silence
        assertEquals(2, events.size)
    }
    
    @Test
    fun `parseGates handles alternation`() {
        val result = TidalParser.parseGates("<1 2>")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        // Cycle 0 should have voice 0
        val eventsC0 = result.pattern.query(Arc(0.0, 1.0))
        assertEquals(1, eventsC0.size)
        assertEquals(0, (eventsC0[0].value as TidalEvent.Gate).voiceIndex)
        
        // Cycle 1 should have voice 1
        val eventsC1 = result.pattern.query(Arc(1.0, 2.0))
        assertEquals(1, eventsC1.size)
        assertEquals(1, (eventsC1[0].value as TidalEvent.Gate).voiceIndex)
    }
    
    @Test
    fun `parseGates rejects invalid voice index`() {
        val result = TidalParser.parseGates("13")
        assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result)
        assertTrue(result.message.contains("1-12"))
    }
    
    @Test
    fun `parseFloats parses float sequence`() {
        val result = TidalParser.parseFloats("0.1 0.2 0.3") { 
            TidalEvent.VoiceTune(0, it) 
        }
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
        
        val tunes = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.VoiceTune).tune }
        assertEquals(listOf(0.1f, 0.2f, 0.3f), tunes)
    }
    
    @Test
    fun `gates extension function works`() {
        val pattern = "1 2 3 4".gates()
        val events = pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
    }

    @Test
    fun `parseGates handles comma stacking`() {
        // "1 2, 3 4" should stack two patterns
        val result = TidalParser.parseGates("1 2, 3 4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        // 2 events from first pattern (0, 1) + 2 events from second pattern (2, 3) = 4 events total
        assertEquals(4, events.size)
        
        // Check that we have synchronous events (at start of cycle)
        val startEvents = events.filter { it.part.start == 0.0 }
        // Should have voice 0 (from first pattern) and voice 2 (from second pattern) at start
        assertEquals(2, startEvents.size)
        val voices = startEvents.map { (it.value as TidalEvent.Gate).voiceIndex }.sorted()
        assertEquals(listOf(0, 2), voices)
    }

    @Test
    fun `parseSounds parses sample events`() {
        val result = TidalParser.parseSounds("bd sn")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(2, events.size)
        
        val samples = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Sample).name }
        assertEquals(listOf("bd", "sn"), samples)
    }

    @Test
    fun `parseGates handles euclid`() {
        val result = TidalParser.parseGates("1(3,8)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        val events = result.pattern.query(Arc.UNIT)
        // 3 events from (3,8) distribution
        assertEquals(3, events.size)
    }

    @Test
    fun `parseGates handles modifiers`() {
        val result = TidalParser.parseGates("1*2 2/2")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        // 1*2 -> 2 events in first half
        // 2/2 -> 1 event in second half (spanning longer)
        // Total query(0,1) -> 2 events for '0', plus '1' starts at 0.5.
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
    }

    @Test
    fun `parseNotes parses simple notes`() {
        val result = TidalParser.parseNotes("c3 e3 g3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
        
        val notes = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Note).midiNote }
        // c3 = 48, e3 = 52, g3 = 55
        assertEquals(listOf(48, 52, 55), notes)
    }

    @Test
    fun `parseNotes handles sharps`() {
        val result = TidalParser.parseNotes("c#3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        // c#3 = 49
        assertEquals(49, (events[0].value as TidalEvent.Note).midiNote)
    }

    @Test
    fun `parseNotes handles flats with b`() {
        val result = TidalParser.parseNotes("eb3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        // eb3 = 51 (e3=52 minus 1)
        assertEquals(51, (events[0].value as TidalEvent.Note).midiNote)
    }

    @Test
    fun `parseNotes handles flats with minus sign`() {
        // Alternative flat notation: e-3 = eb3
        val result = TidalParser.parseNotes("e-3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        // e-3 = eb3 = 51
        assertEquals(51, (events[0].value as TidalEvent.Note).midiNote)
    }

    @Test
    fun `parseNotes handles mixed flat notations`() {
        // Both eb and e- should parse to the same MIDI note
        val resultB = TidalParser.parseNotes("a-3 b-3")
        val resultMinus = TidalParser.parseNotes("ab3 bb3") 
        
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(resultB)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(resultMinus)
        
        val notesB = resultB.pattern.query(Arc.UNIT)
            .sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Note).midiNote }
        val notesMinus = resultMinus.pattern.query(Arc.UNIT)
            .sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Note).midiNote }
        
        // ab3=56, bb3=58 and a-3=56, b-3=58
        assertEquals(notesMinus, notesB)
    }
}

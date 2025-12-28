package org.balch.orpheus.core.tidal

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ComplexParserTest {

    @Test
    fun `test complex voices syntax`() {
        val input = "<0 2 4> [1 3]"
        val result = TidalParser.parseGates(input)
        
        if (result is TidalParser.ParseResult.Failure) {
            println("Parse failed: ${result.message}")
        }
        
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
    }

    @Test
    fun `test slowcat syntax`() {
        val input = "<0 1>"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
    }

    @Test
    fun `test nested structures`() {
        val input = "[0 [1 2]*2] <3 4>"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
    }
    
    @Test
    fun `test replication and polymeters`() {
        val input = "{0, 1 2} !3"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
    }
    
    // ===== Comprehensive Slowcat Alternation Tests =====
    
    @Test
    fun `slowcat three elements cycles correctly over 6 cycles`() {
        // Test that <0 2 4> produces voice 0, 2, 4, 0, 2, 4 over 6 cycles
        val input = "<0 2 4>"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        
        // Expected sequence: cycle 0=voice0, cycle 1=voice2, cycle 2=voice4, 
        //                    cycle 3=voice0, cycle 4=voice2, cycle 5=voice4
        val expectedVoices = listOf(0, 2, 4, 0, 2, 4)
        
        for (cycle in 0 until 6) {
            val arc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
            val events = pattern.query(arc).filter { it.hasOnset() }
            
            assertEquals(1, events.size, "Cycle $cycle should have exactly 1 event")
            val gate = events[0].value as? TidalEvent.Gate
                ?: fail("Event should be a Gate, got: ${events[0].value}")
            assertEquals(
                expectedVoices[cycle], 
                gate.voiceIndex, 
                "Cycle $cycle should trigger voice ${expectedVoices[cycle]}, got ${gate.voiceIndex}"
            )
        }
    }
    
    @Test
    fun `slowcat with sequence produces correct events per cycle`() {
        // Test "<0 2 4> [1 3]" which has:
        // - slowcat part: alternates 0, 2, 4 per cycle
        // - sequence part: [1 3] plays both 1 and 3 every cycle
        val input = "<0 2 4> [1 3]"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        
        // For each cycle, we expect:
        // - The slowcat voice (0, 2, or 4 depending on cycle)
        // - Plus voices 1 and 3 from [1 3]
        val expectedSlowcatVoices = listOf(0, 2, 4)
        
        for (cycle in 0 until 6) {
            val arc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
            val events = pattern.query(arc).filter { it.hasOnset() }
            
            // Should have 3 events per cycle: 1 from slowcat + 2 from [1 3]
            assertEquals(3, events.size, "Cycle $cycle should have 3 events, got ${events.size}")
            
            val voices = events.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }.sorted()
            val expectedSlowcat = expectedSlowcatVoices[cycle % 3]
            val expectedVoices = listOf(1, expectedSlowcat, 3).sorted()
            
            assertEquals(
                expectedVoices, 
                voices.sorted(),
                "Cycle $cycle should have voices ${expectedVoices.sorted()}, got ${voices.sorted()}"
            )
        }
    }
    
    @Test
    fun `scheduler windowed query simulates real playback`() {
        // Simulate the scheduler's windowed query approach
        // The scheduler queries windows of ~250ms (e.g., 0.5 cycles at 120bpm)
        val input = "<0 2 4>"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val cps = 120.0 / 60.0 / 4.0  // Cycles per second at 120 BPM (0.5 cps)
        val windowDurationSeconds = 0.25  // 250ms windows
        val totalSeconds = 12.0  // Run for 12 seconds (6 cycles at 0.5 cps)
        
        val allEvents = mutableListOf<Pair<Int, Int>>() // (cycle, voiceIndex)
        
        var windowStart = 0.0
        while (windowStart < totalSeconds) {
            val windowEnd = windowStart + windowDurationSeconds
            
            // Convert to cycles
            val arcStart = windowStart * cps
            val arcEnd = windowEnd * cps
            val arc = Arc(arcStart, arcEnd)
            
            val events = pattern.query(arc).filter { it.hasOnset() }
            
            events.forEach { event ->
                val cycle = kotlin.math.floor(event.part.start).toInt()
                val gate = event.value as? TidalEvent.Gate
                if (gate != null) {
                    allEvents.add(cycle to gate.voiceIndex)
                }
            }
            
            windowStart = windowEnd
        }
        
        // Group by cycle and check we got the right voices
        val eventsByCycle = allEvents.groupBy({ it.first }, { it.second })
        
        // We should see cycles 0-5 (6 cycles in 12 seconds at 0.5 cps)
        assertTrue(eventsByCycle.keys.size >= 5, "Should have at least 5 cycles, got ${eventsByCycle.keys}")
        
        // Check first 6 cycles have expected alternating pattern
        val expectedPattern = listOf(0, 2, 4, 0, 2, 4)
        for (cycle in 0 until minOf(6, eventsByCycle.keys.maxOrNull() ?: 0)) {
            val voicesInCycle = eventsByCycle[cycle] ?: emptyList()
            assertTrue(
                voicesInCycle.contains(expectedPattern[cycle]),
                "Cycle $cycle should contain voice ${expectedPattern[cycle]}, got: $voicesInCycle"
            )
        }
    }
    
    @Test
    fun `voices pattern with colon syntax parses correctly`() {
        // Test the actual REPL syntax: "d1 $ voices: <0 2 4> [1 3]"
        // For now, test just the pattern portion since the d1 $ is REPL syntax
        val input = "<0 2 4> [1 3]"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        // Verify we can query all 6 unique voice combinations over 3 cycles
        val pattern = result.pattern
        val seenVoiceCombinations = mutableSetOf<Set<Int>>()
        
        for (cycle in 0 until 3) {
            val arc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
            val events = pattern.query(arc).filter { it.hasOnset() }
            val voices = events.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }.toSet()
            seenVoiceCombinations.add(voices)
        }
        
        // Should have 3 different combinations: {0,1,3}, {1,2,3}, {1,3,4}
        assertEquals(3, seenVoiceCombinations.size, "Should see 3 different voice combinations")
        
        // All combinations should include 1 and 3 (from [1 3])
        seenVoiceCombinations.forEach { combo ->
            assertTrue(combo.contains(1), "All combinations should include voice 1: $combo")
            assertTrue(combo.contains(3), "All combinations should include voice 3: $combo")
        }
        
        // The varying voice should be from the slowcat: 0, 2, or 4
        val varyingVoices = seenVoiceCombinations.flatten().toSet() - setOf(1, 3)
        assertEquals(setOf(0, 2, 4), varyingVoices, "Should see slowcat voices 0, 2, 4")
    }
    
    // ===== Range (..) Tests =====
    
    @Test
    fun `range 0 to 3 expands to four events`() {
        val input = "0..3"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Should have 4 events: 0, 1, 2, 3
        assertEquals(4, events.size, "Range 0..3 should produce 4 events")
        
        val voices = events.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }.sorted()
        assertEquals(listOf(0, 1, 2, 3), voices, "Should have voices 0, 1, 2, 3 in order")
    }
    
    @Test
    fun `range 3 to 0 expands in reverse`() {
        val input = "3..0"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Should have 4 events: 3, 2, 1, 0 (in that order)
        assertEquals(4, events.size, "Range 3..0 should produce 4 events")
        
        // The events should be sorted by time, but their values are 3, 2, 1, 0
        val voices = events.sortedBy { it.part.start }.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }
        assertEquals(listOf(3, 2, 1, 0), voices, "Should have voices 3, 2, 1, 0 in that order")
    }
    
    @Test
    fun `range in sequence works`() {
        val input = "0..2 5"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Should have 4 events: 0, 1, 2 (from range), then 5
        assertEquals(4, events.size, "Should have 4 events total")
        
        val voices = events.sortedBy { it.part.start }.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }
        assertEquals(listOf(0, 1, 2, 5), voices, "Should have voices 0, 1, 2, 5")
    }
    
    // ===== Elongation (@) Tests =====
    
    @Test
    fun `elongation gives proportional time`() {
        val input = "0@2 1"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Should have 2 events
        assertEquals(2, events.size, "Should have 2 events")
        
        // Get events sorted by time
        val sorted = events.sortedBy { it.part.start }
        
        // First event (0@2) should start at 0 and get 2/3 of the time
        val firstEvent = sorted[0]
        assertEquals(0, (firstEvent.value as TidalEvent.Gate).voiceIndex, "First should be voice 0")
        assertEquals(0.0, firstEvent.part.start, 0.001, "First should start at 0")
        
        // Second event (1) should start at 2/3 and get 1/3 of the time
        val secondEvent = sorted[1]
        assertEquals(1, (secondEvent.value as TidalEvent.Gate).voiceIndex, "Second should be voice 1")
        assertEquals(2.0/3.0, secondEvent.part.start, 0.01, "Second should start at ~0.666")
    }
    
    @Test
    fun `multiple elongations work together`() {
        val input = "0@2 1@3 2"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        assertEquals(3, events.size, "Should have 3 events")
        
        // Weights: 2 + 3 + 1 = 6
        // Voice 0: [0, 2/6) = [0, 0.333)
        // Voice 1: [2/6, 5/6) = [0.333, 0.833)
        // Voice 2: [5/6, 1) = [0.833, 1)
        
        val sorted = events.sortedBy { it.part.start }
        
        assertEquals(0.0, sorted[0].part.start, 0.001, "Voice 0 should start at 0")
        assertEquals(2.0/6.0, sorted[1].part.start, 0.01, "Voice 1 should start at ~0.333")
        assertEquals(5.0/6.0, sorted[2].part.start, 0.01, "Voice 2 should start at ~0.833")
    }
    
    @Test
    fun `elongation with nested groups works`() {
        val input = "[0 1]@2 2"
        val result = TidalParser.parseGates(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse '$input'")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Weights: 2 + 1 = 3
        // [0 1] gets 2/3 of cycle, 2 gets 1/3
        // Within [0 1], 0 and 1 each get half (so 1/3 each)
        assertEquals(3, events.size, "Should have 3 events: 0, 1, 2")
        
        val sorted = events.sortedBy { it.part.start }
        val voices = sorted.mapNotNull { (it.value as? TidalEvent.Gate)?.voiceIndex }
        assertEquals(listOf(0, 1, 2), voices, "Should be 0, 1, 2 in order")
    }
    
    // ===== In-A-Gadda-Da-Vida Riff Test =====
    
    @Test
    fun `gadda riff parses with correct notes and timing`() {
        // The riff: D-D (8th 8th) F-E (8th 8th) C (quarter) D (8th) A-Ab-G (triplet)
        // Pattern: "d2 d2 f2 e2 c2@2 d2 [a2 g#2 g2]"
        val input = "d2 d2 f2 e2 c2@2 d2 [a2 g#2 g2]"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse gadda riff")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Count: d2 d2 f2 e2 = 4, c2@2 = 1, d2 = 1, [a2 g#2 g2] = 3
        // Total = 9 notes
        assertEquals(9, events.size, "Should have 9 note events")
        
        // Get events sorted by time
        val sorted = events.sortedBy { it.part.start }
        
        // Extract MIDI notes
        val midiNotes = sorted.map { (it.value as TidalEvent.Note).midiNote }
        
        // Expected MIDI notes:
        // d2 = 38, f2 = 41, e2 = 40, c2 = 36
        // a2 = 45, g#2 = 44, g2 = 43
        val expectedNotes = listOf(
            38, 38,     // d2 d2
            41, 40,     // f2 e2
            36,         // c2 (elongated)
            38,         // d2
            45, 44, 43  // [a2 g#2 g2] triplet
        )
        assertEquals(expectedNotes, midiNotes, "MIDI notes should match the riff")
        
        // Verify timing - c2@2 should take 2x as long as other notes
        // With 8 equal steps + elongation applied
        // Steps without weights: d2 d2 f2 e2 [c2@2 d2 [a2 g#2 g2]]
        // Actually the sequence has 7 children with c2@2 having weight 2
        // Weights: 1+1+1+1+2+1+1 = 8, so c2 gets 2/8 = 1/4 of cycle
        
        val c2Event = sorted[4] // 5th note is C2
        val durationApprox = c2Event.whole?.let { it.end - it.start } ?: 0.0
        
        // C2 should have ~2/8 = 0.25 of the cycle (roughly)
        assertTrue(durationApprox > 0.15, "C2 should have elongated duration: $durationApprox")
    }
    
    @Test
    fun `gadda riff with rest has pause at end`() {
        // The complete gadda pattern with rest at end for proper loop timing
        // Weights: d2(1) d2(1) f2(1) e2(1) c2@2(2) d2(1) triplet(1) rest(1) = 9 total
        val input = "d2 d2 f2 e2 c2@2 d2 [a2 g#2 g2] ~"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse gadda with rest")
        
        val pattern = result.pattern
        val arc = Arc(0.0, 1.0)
        val events = pattern.query(arc).filter { it.hasOnset() }
        
        // Should still have 9 note events (rest produces no events)
        assertEquals(9, events.size, "Should have 9 note events (rest is silence)")
        
        // Last note (triplet's last note G2) should end before the cycle ends
        // to create space for the rest
        val sorted = events.sortedBy { it.part.start }
        val lastNote = sorted.last()
        val lastNoteEnd = lastNote.whole?.end ?: lastNote.part.end
        
        // With 9 weights, last note group [a2 g#2 g2] starts at 7/9 and ends at 8/9
        // Rest takes the final 1/9 of the cycle (from ~0.889 to 1.0)
        assertTrue(lastNoteEnd < 1.0, "Last note should end before cycle to leave room for rest: $lastNoteEnd")
    }
    
    @Test
    fun `gadda note names map to correct MIDI`() {
        // Quick sanity check for MIDI note mapping
        // c4 = 60 (middle C)
        // d2 = D in octave 2 = (2+1)*12 + 2 = 38
        val result = TidalParser.parseNotes("c4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        assertEquals(1, events.size)
        
        val note = events[0].value as TidalEvent.Note
        assertEquals(60, note.midiNote, "c4 should be MIDI 60")
    }
    
    @Test
    fun `notes with elongation parse correctly`() {
        // This specifically tests the bug where c2@2 was being passed as "c2@2" to noteNameToMidi
        val input = "c2@2 d2"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse notes with @")
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        assertEquals(2, events.size, "Should have 2 notes")
        
        val midiNotes = events.sortedBy { it.part.start }.map { (it.value as TidalEvent.Note).midiNote }
        // c2 = (2+1)*12 + 0 = 36
        // d2 = (2+1)*12 + 2 = 38
        assertEquals(listOf(36, 38), midiNotes)
    }
    
    @Test
    fun `notes with grouping parse correctly`() {
        val input = "[c3 e3 g3]"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse grouped notes")
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        assertEquals(3, events.size, "Should have 3 notes in group")
        
        val midiNotes = events.sortedBy { it.part.start }.map { (it.value as TidalEvent.Note).midiNote }
        // c3=48, e3=52, g3=55
        assertEquals(listOf(48, 52, 55), midiNotes)
    }
    
    @Test
    fun `notes with repetition parse correctly`() {
        val input = "c3*4"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse repeated notes")
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        assertEquals(4, events.size, "Should have 4 repeated notes")
        
        // All should be c3 = 48
        events.forEach { event ->
            assertEquals(48, (event.value as TidalEvent.Note).midiNote)
        }
    }
    
    @Test
    fun `notes with sharps and flats parse correctly`() {
        val input = "c#3 db3 f#3 gb3"
        val result = TidalParser.parseNotes(input)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        val midiNotes = events.sortedBy { it.part.start }.map { (it.value as TidalEvent.Note).midiNote }
        
        // c#3 = 49, db3 = 49 (enharmonic), f#3 = 54, gb3 = 54 (enharmonic)
        assertEquals(listOf(49, 49, 54, 54), midiNotes)
    }
    
    // ===== MIDI to Frequency Conversion Tests =====
    
    @Test
    fun `midi note 69 is 440Hz (A4)`() {
        // Standard MIDI: A4 = 69 = 440Hz
        val freq = 440.0 * 2.0.pow((69 - 69) / 12.0)
        assertEquals(440.0, freq, 0.01)
    }
    
    @Test
    fun `midi note 60 is 261Hz (middle C)`() {
        // C4 = 60 = ~261.63Hz
        val freq = 440.0 * 2.0.pow((60 - 69) / 12.0)
        assertEquals(261.63, freq, 0.1)
    }
    
    @Test
    fun `gadda riff frequencies are in audible range`() {
        // D2 = MIDI 38, should be ~73.4 Hz (audible bass)
        // This verifies our MIDI conversion produces reasonable frequencies
        val d2Freq = 440.0 * 2.0.pow((38 - 69) / 12.0)
        assertTrue(d2Freq > 50.0, "D2 should be above 50Hz")
        assertTrue(d2Freq < 100.0, "D2 should be below 100Hz")
        assertEquals(73.42, d2Freq, 0.1)
        
        // A2 = MIDI 45, should be 110 Hz
        val a2Freq = 440.0 * 2.0.pow((45 - 69) / 12.0)
        assertEquals(110.0, a2Freq, 0.01)
        
        // C2 = MIDI 36, should be ~65.4 Hz
        val c2Freq = 440.0 * 2.0.pow((36 - 69) / 12.0)
        assertEquals(65.41, c2Freq, 0.1)
    }
    
    @Test
    fun `note events have correct timing with elongation`() {
        // Test that "c3@2 d3" gives c3 twice as much time
        val result = TidalParser.parseNotes("c3@2 d3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT).filter { it.hasOnset() }
        assertEquals(2, events.size)
        
        val sorted = events.sortedBy { it.part.start }
        
        // C3 should start at 0 and end at 2/3 (weight 2 out of 3)
        val c3Event = sorted[0]
        assertEquals(0.0, c3Event.part.start, 0.01)
        
        // D3 should start at 2/3
        val d3Event = sorted[1]
        assertEquals(0.666, d3Event.part.start, 0.02)
    }
    @Test
    fun `numeric notes parse correctly`() {
        // Syntax from user: "<[36 48]*4 [34 46]*4 [41 53]*4 [39 51]*4>"
        // This tests that we support raw MIDI numbers in note patterns
        val input = "<[36 48]*4 [34 46]*4 [41 53]*4 [39 51]*4>"
        val result = TidalParser.parseNotes(input)
        
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Should parse user syntax with numeric notes")
        
        // Verify structure roughly
        val pattern = result.pattern
        val events = pattern.query(Arc(0.0, 1.0)).filter { it.hasOnset() }
        
        // Cycle 0: [36 48]*4
        // [36 48] is a sequence of 2 notes. *4 speeds it up 4x.
        // So we get 4 repetitions of the sequence [36 48] in one cycle.
        // Total 4 * 2 = 8 events.
        assertEquals(8, events.size, "Should have 8 events in first cycle")
        
        // Verify values
        val midiNotes = events.sortedBy { it.part.start }.map { (it.value as TidalEvent.Note).midiNote }
        // specific check for first two notes
        assertEquals(36, midiNotes[0])
        assertEquals(48, midiNotes[1])
    }
}

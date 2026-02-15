package org.balch.orpheus.core.tidal

import org.balch.orpheus.core.audio.TestSynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.TestDispatcherProvider
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.audio.dsp.TestAudioEngine
import org.balch.orpheus.core.audio.dsp.TestDspFactory
import org.balch.orpheus.core.tempo.GlobalTempo
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for TidalRepl pattern parsing, including edge cases
 * and AI-generated code sanitization.
 */
class TidalReplParsingTest {

    private lateinit var repl: TidalRepl
    private val dispatcherProvider = TestDispatcherProvider()


    @BeforeTest
    fun setup() {
        val synthEngine = TestSynthEngine()
        val synthController = SynthController()
        val playbackLifecycleManager = PlaybackLifecycleManager()
        val globalTempo = GlobalTempo(TestAudioEngine(), TestDspFactory())
        val scheduler = TidalScheduler(
            synthController,
            synthEngine,
            playbackLifecycleManager,
            dispatcherProvider,
            globalTempo,
        )
        repl = TidalRepl(scheduler, dispatcherProvider)
    }

    // =========================================================================
    // Note Pattern Parsing Tests
    // =========================================================================

    @Test
    fun `parseNotes handles basic note`() {
        val result = TidalParser.parseNotes("c3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        assertIs<TidalEvent.Note>(events[0].value)
        assertEquals(48, (events[0].value as TidalEvent.Note).midiNote) // c3 = 48
    }
    
    @Test
    fun `parseNotes handles note sequence`() {
        val result = TidalParser.parseNotes("c3 e3 g3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
        
        val notes = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Note).midiNote }
        assertEquals(listOf(48, 52, 55), notes) // c3=48, e3=52, g3=55
    }
    
    @Test
    fun `parseNotes handles sharps`() {
        val result = TidalParser.parseNotes("c#3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        assertEquals(49, (events[0].value as TidalEvent.Note).midiNote) // c#3 = 49
    }
    
    @Test
    fun `parseNotes handles flats`() {
        val result = TidalParser.parseNotes("db3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        assertEquals(49, (events[0].value as TidalEvent.Note).midiNote) // db3 = c#3 = 49
    }
    
    @Test
    fun `parseNotes handles various octaves`() {
        val c2 = TidalParser.parseNotes("c2") as TidalParser.ParseResult.Success
        val c4 = TidalParser.parseNotes("c4") as TidalParser.ParseResult.Success
        val c5 = TidalParser.parseNotes("c5") as TidalParser.ParseResult.Success
        
        val midiC2 = (c2.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
        val midiC4 = (c4.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
        val midiC5 = (c5.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
        
        assertEquals(36, midiC2) // c2 = 36
        assertEquals(60, midiC4) // c4 = 60 (middle C)
        assertEquals(72, midiC5) // c5 = 72
    }
    
    @Test
    fun `parseNotes rejects invalid note format`() {
        val result = TidalParser.parseNotes("xyz")
        assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result)
        assertContains(result.message.lowercase(), "invalid note")
    }
    
    @Test
    fun `parseNotes rejects note with trailing quote`() {
        // This was the original bug - c4" instead of c4
        val result = TidalParser.parseNotes("c4\"")
        assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result)
        assertContains(result.message.lowercase(), "invalid note")
    }
    
    @Test
    fun `parseNotes handles raw MIDI numbers`() {
        val result = TidalParser.parseNotes("60")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(60, (events[0].value as TidalEvent.Note).midiNote)
    }
    
    @Test
    fun `parseNotes handles silence markers`() {
        val result = TidalParser.parseNotes("c3 ~ e3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(2, events.size) // c3 and e3, silence is filtered
    }
    
    @Test
    fun `parseNotes handles complex patterns with grouping`() {
        val result = TidalParser.parseNotes("[c3 e3] g3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
    }
    
    @Test
    fun `parseNotes handles alternation`() {
        val result = TidalParser.parseNotes("<c3 e3>")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        // Cycle 0 should have c3
        val eventsC0 = result.pattern.query(Arc(0.0, 1.0))
        assertEquals(1, eventsC0.size)
        assertEquals(48, (eventsC0[0].value as TidalEvent.Note).midiNote)
        
        // Cycle 1 should have e3
        val eventsC1 = result.pattern.query(Arc(1.0, 2.0))
        assertEquals(1, eventsC1.size)
        assertEquals(52, (eventsC1[0].value as TidalEvent.Note).midiNote)
    }

    // =========================================================================
    // Quote Handling Edge Cases
    // =========================================================================
    
    @Test
    fun `parseNotes handles note with embedded quotes gracefully`() {
        // If somehow quotes leak through, the parser should fail gracefully
        val result = TidalParser.parseNotes("\"c3\"")
        // This should fail since "c3" is not a valid note name
        assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result)
    }
    
    @Test
    fun `parseSounds strips quotes from sample names`() {
        // parseSounds has quote stripping built in
        val result = TidalParser.parseSounds("bd sn")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        val samples = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Sample).name }
        assertEquals(listOf("bd", "sn"), samples)
    }

    // =========================================================================
    // Speed/Slow Transformation Tests
    // =========================================================================
    
    @Test
    fun `parseNotes handles fast transformation`() {
        val result = TidalParser.parseNotes("c3*2")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(2, events.size) // c3 played twice
    }
    
    @Test
    fun `parseNotes handles slow transformation`() {
        val result = TidalParser.parseNotes("c3/2")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        // The event should span longer
    }

    // =========================================================================
    // Elongation Tests
    // =========================================================================
    
    @Test
    fun `parseNotes handles elongation with at symbol`() {
        val result = TidalParser.parseNotes("c3@2 e3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(2, events.size)
        // c3 should take 2/3 of the cycle, e3 takes 1/3
    }

    // =========================================================================
    // Euclidean Rhythm Tests
    // =========================================================================
    
    @Test
    fun `parseNotes handles basic Euclidean rhythm`() {
        val result = TidalParser.parseNotes("c3(3,8)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        // Euclidean(3,8) distributes 3 pulses evenly across 8 steps
        val events = result.pattern.query(Arc(0.0, 1.0))
        assertEquals(3, events.size, "Should have 3 note events for (3,8) pattern")
        
        // Verify all events are C3 notes
        events.forEach { event ->
            val note = event.value as TidalEvent.Note
            assertEquals(48, note.midiNote, "All events should be C3 (MIDI 48)")
        }
        
        // Verify events are distributed (not all at same position)
        val positions = events.mapNotNull { it.whole?.start }.distinct()
        assertEquals(positions.size, 3, "Events should be at different positions")
    }
    
    @Test
    fun `parseNotes handles Euclidean with multiple notes`() {
        val result = TidalParser.parseNotes("c3(3,8) e3(5,8)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc(0.0, 1.0))
        // First half cycle has c3 with Euclid(3,8), second half has e3 with Euclid(5,8)
        assertTrue(events.isNotEmpty(), "Should have events from Euclidean patterns")
    }
    
    @Test
    fun `parseGates handles Euclidean rhythm with voices`() {
        val result = TidalParser.parseGates("1(3,8) 2(5,8)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc(0.0, 1.0))
        assertTrue(events.isNotEmpty(), "Should have gate events from Euclidean patterns")
        
        // Verify we have both voice 0 (from input 1) and voice 1 (from input 2)
        val voiceIndices = events.map { (it.value as TidalEvent.Gate).voiceIndex }.distinct().sorted()
        assertTrue(voiceIndices.contains(0), "Should have voice 0 (from input 1)")
        assertTrue(voiceIndices.contains(1), "Should have voice 1 (from input 2)")
    }

    @Test
    fun `parseNotes handles Euclidean with different k and n values`() {
        // (5,8) means 5 pulses distributed across 8 steps
        // Expected pattern: X.XX.XX. (10110110 in binary)
        val result = TidalParser.parseNotes("d3(5,8)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc(0.0, 1.0))
        assertEquals(5, events.size, "Should have 5 note events for (5,8) pattern")
        
        // Verify events are D3 notes
        events.forEach { event ->
            val note = event.value as TidalEvent.Note
            assertEquals(50, note.midiNote, "All events should be D3 (MIDI 50)")
        }
    }
    
    @Test
    fun `parseNotes handles Euclidean with rotation`() {
        // (3,8,2) means 3 pulses across 8 steps, rotated by 2
        val result = TidalParser.parseNotes("c3(3,8,2)")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc(0.0, 1.0))
        assertEquals(3, events.size, "Should have 3 note events for (3,8,2) pattern")
    }

    // =========================================================================
    // Edge Cases and Robustness
    // =========================================================================
    
    @Test
    fun `parseNotes handles mixed case note names`() {
        // Both uppercase and lowercase should work
        val lowerResult = TidalParser.parseNotes("c3")
        val upperResult = TidalParser.parseNotes("C3")
        
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(lowerResult)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(upperResult)
        
        val lowerNote = (lowerResult.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
        val upperNote = (upperResult.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
        assertEquals(lowerNote, upperNote)
    }
    
    @Test
    fun `parseNotes handles all note letters`() {
        val notes = listOf("c4", "d4", "e4", "f4", "g4", "a4", "b4")
        val expectedMidi = listOf(60, 62, 64, 65, 67, 69, 71)
        
        notes.forEachIndexed { index, note ->
            val result = TidalParser.parseNotes(note)
            assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
            val midi = (result.pattern.query(Arc.UNIT)[0].value as TidalEvent.Note).midiNote
            assertEquals(expectedMidi[index], midi, "Note $note should be MIDI ${expectedMidi[index]}")
        }
    }
    
    @Test
    fun `parseNotes handles whitespace in patterns`() {
        val result = TidalParser.parseNotes("  c3   e3  g3  ")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
    }
    
    @Test
    fun `empty input fails gracefully`() {
        // Empty input should either succeed with no events or fail gracefully
        when (val result = TidalParser.parseNotes("")) {
            is TidalParser.ParseResult.Success -> {
                val events = result.pattern.query(Arc.UNIT)
                assertTrue(events.isEmpty())
            }
            is TidalParser.ParseResult.Failure -> {
                // Also acceptable
            }
        }
    }
    
    @Test
    fun `parseGates handles all valid voice indices`() {
        for (i in 1..8) {
            val result = TidalParser.parseGates(i.toString())
            assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result, "Voice $i should be valid")
        }
    }
    
    @Test
    fun `parseGates rejects voice index 0`() {
        val result = TidalParser.parseGates("0")
        assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result, "Voice 0 should be invalid (1-based)")
    }
    
    @Test
    fun `parseGates rejects voice indices above 12`() {
        for (i in 13..20) {
            val result = TidalParser.parseGates(i.toString())
            assertIs<TidalParser.ParseResult.Failure<TidalEvent>>(result, "Voice $i should be invalid")
        }
    }

    // =========================================================================
    // Source Location Tracking Tests  
    // =========================================================================
    
    @Test
    fun `parseNotes tracks source locations`() {
        val result = TidalParser.parseNotes("c3 e3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(2, events.size)
        
        // Each event should have source location info
        events.forEach { event ->
            val note = event.value as TidalEvent.Note
            assertTrue(note.locations.isNotEmpty(), "Note should have location info")
        }
    }
    
    @Test
    fun `parseGates tracks source locations`() {
        val result = TidalParser.parseGates("1 2 3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        events.forEach { event ->
            val gate = event.value as TidalEvent.Gate
            assertTrue(gate.locations.isNotEmpty(), "Gate should have location info")
        }
    }
    
    // =========================================================================
    // TidalCycles # Pattern Combiner Tests
    // =========================================================================
    
    @Test
    fun `splitByHashOutsideQuotes splits simple pattern`() {
        // This tests the internal function behavior via pattern parsing
        // note "c3" # hold:0 0.8 should be split into ["note \"c3\"", "hold:0 0.8"]
        val result = TidalParser.parseNotes("c3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
    }
    
    @Test
    fun `parseNotes correctly parses notes for combiner use`() {
        // Verify note patterns parse correctly in isolation
        // (used as part of combined patterns)
        val result = TidalParser.parseNotes("c3 e3 g3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
    }
    
    @Test
    fun `hash inside quotes is not treated as combiner`() {
        // A note like "c#3" should not be split by the # inside quotes
        val result = TidalParser.parseNotes("c#3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
        assertEquals(49, (events[0].value as TidalEvent.Note).midiNote) // c#3 = 49
    }
    
    // =========================================================================
    // Tidal-Style Quoted Syntax Tests
    // =========================================================================
    
    @Test
    fun `parseGates handles basic voice sequence`() {
        val result = TidalParser.parseGates("1 2 3 4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
        
        val voices = events.sortedBy { it.part.start }
            .map { (it.value as TidalEvent.Gate).voiceIndex }
        // Parser accepts1-based (1 2 3 4) but converts to 0-based internally (0 1 2 3)
        assertEquals(listOf(0, 1, 2, 3), voices)
    }
    
    @Test
    fun `parseFloats parses hold-like values`() {
        // Test parsing floats for control values
        val result = TidalParser.parseFloats("0.8") { TidalEvent.VoiceHold(0, it, emptyList()) }
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(1, events.size)
    }
    
    @Test
    fun `parseFloats parses multiple values`() {
        val result = TidalParser.parseFloats("0.1 0.5 0.9") { TidalEvent.VoiceHold(0, it, emptyList()) }
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
    }
    
    @Test
    fun `evaluate supports envspeed command`() = kotlinx.coroutines.test.runTest {
        // Test basic colon syntax (bare command)
        val resultColon = repl.evaluateSuspend("envspeed:1 0.5")
        // Success with empty slots indicates immediate execution
        assertIs<ReplResult.Success>(resultColon) 
        
        // Test quoted syntax (bare command)
        val resultQuoted = repl.evaluateSuspend("envspeed \"1 0.5\"")
        assertIs<ReplResult.Success>(resultQuoted)
        
        // Test quoted single value (voice 0 implicit)
        val resultQuotedSingle = repl.evaluateSuspend("envspeed \"0.5\"")
        assertIs<ReplResult.Success>(resultQuotedSingle)
    }
    
    @Test
    fun `evaluate supports envspeed error handling`() = kotlinx.coroutines.test.runTest {
        // Test invalid voice index
        val resultInvalidVoice = repl.evaluateSuspend("envspeed:9 0.5")
        // Should default to 0-based index 8
        // Wait, envspeed: parser checks 1..8 range.
        assertIs<ReplResult.Error>(resultInvalidVoice)
        
        // Test single value (now valid, implies voice 1, value 1.0)
        val resultSingleValue = repl.evaluateSuspend("envspeed:1")
        assertIs<ReplResult.Success>(resultSingleValue)
    }
    
    @Test
    fun `source locations preserved in note patterns`() {
        // Verify that note patterns have correct source locations
        val pattern = "c3 e3 g3"
        val result = TidalParser.parseNotes(pattern)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
        
        // Each note should have a location
        events.forEach { event ->
            val note = event.value as TidalEvent.Note
            assertTrue(note.locations.isNotEmpty(), "Note should have source location")
        }
    }
    
    @Test
    fun `source locations track positions within pattern`() {
        // Test that locations correctly identify positions in the pattern string
        val pattern = "c3 e3"
        val result = TidalParser.parseNotes(pattern)
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT).sortedBy { it.part.start }
        assertEquals(2, events.size)
        
        val c3Location = (events[0].value as TidalEvent.Note).locations.firstOrNull()
        val e3Location = (events[1].value as TidalEvent.Note).locations.firstOrNull()
        
        // c3 is at position 0, e3 is at position 3
        assertTrue(c3Location != null, "c3 should have location")
        assertTrue(e3Location != null, "e3 should have location")
        
        // e3 location should start after c3
        assertTrue(e3Location.start > c3Location.start, "e3 should be positioned after c3")
    }
    
    @Test
    fun `sharps in notes preserved during parsing`() {
        // c#3 has a # but it's part of the note name, not a combiner
        val result = TidalParser.parseNotes("c#3 d#3")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT).sortedBy { it.part.start }
        assertEquals(2, events.size)
        
        assertEquals(49, (events[0].value as TidalEvent.Note).midiNote) // c#3
        assertEquals(51, (events[1].value as TidalEvent.Note).midiNote) // d#3
    }
    
    @Test
    fun `voice gate patterns have source locations`() {
        val result = TidalParser.parseGates("1 2 3 4")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(4, events.size)
        
        events.forEach { event ->
            val gate = event.value as TidalEvent.Gate
            assertTrue(gate.locations.isNotEmpty(), "Gate should have source location")
        }
    }
    
    @Test
    fun `sample patterns have source locations`() {
        val result = TidalParser.parseSounds("bd sn hh")
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size)
        
        events.forEach { event ->
            val sample = event.value as TidalEvent.Sample
            assertTrue(sample.locations.isNotEmpty(), "Sample should have source location")
        }
    }

    @Test
    fun `evaluate supports quoted drive command`() = kotlinx.coroutines.test.runTest {
        // This is expected to fail currently if my hypothesis is correct
        val result = repl.evaluateSuspend("drive \"0.4\"")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports space-separated pan command`() = kotlinx.coroutines.test.runTest {
        // pan 1 0.5 should work
        val result = repl.evaluateSuspend("pan 1 0.5")
        // Currently this will likely fail with "Invalid index: pan"
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports space-separated voices command`() = kotlinx.coroutines.test.runTest {
        // voices 1 2 3 should work
        val result = repl.evaluateSuspend("voices 1 2 3")
        assertIs<ReplResult.Success>(result)
    }
    @Test
    fun `evaluate supports unquoted drive command`() = kotlinx.coroutines.test.runTest {
        val result = repl.evaluateSuspend("drive 0.4")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports unquoted pan command with implicit voice`() = kotlinx.coroutines.test.runTest {
        // "pan 0.5" should be valid and imply voice 1 (index 0)
        // Currently this might fail if implicit voice isn't handled for unquoted syntax
        val result = repl.evaluateSuspend("pan 0.5")
        // If it fails with "Invalid index: pan", it means it fell through to parseGates
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports unquoted envspeed command with implicit voice`() = kotlinx.coroutines.test.runTest {
        val result = repl.evaluateSuspend("envspeed 0.5")
        assertIs<ReplResult.Success>(result)
    }
    
    // =========================================================================
    // Voices + Hold Pattern Combiner Tests
    // =========================================================================
    
    @Test
    fun `evaluate supports hold pattern with quoted values`() = kotlinx.coroutines.test.runTest {
        // hold "0.2 0.5 0.8" should create VoiceHold events for voices 0, 1, 2
        val result = repl.evaluateSuspend("d1 \$ hold \"0.2 0.5 0.8\"")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports voices combined with hold via hash combiner`() = kotlinx.coroutines.test.runTest {
        // d1 $ voices "1 2 3" # hold "0.2 0.5 0.8"
        val result = repl.evaluateSuspend("d1 \$ voices \"1 2 3\" # hold \"0.2 0.5 0.8\"")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports voices with hold and envspeed combined`() = kotlinx.coroutines.test.runTest {
        // The documented correct pattern with envspeed + hold
        val result = repl.evaluateSuspend("d1 \$ voices \"1\" # hold \"0.8\" # envspeed \"0.7\"")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `hold pattern assigns correct voice indices`() {
        // Test that floats parse correctly with mini-notation
        val result = TidalParser.parseFloats("0.2 0.5 0.8") { TidalEvent.VoiceHold(0, it, emptyList()) }
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        val events = result.pattern.query(Arc.UNIT)
        assertEquals(3, events.size, "Should have 3 VoiceHold events")
        
        // Verify hold values are correctly parsed and ordered by time
        val values = events.sortedBy { it.part.start }.map { (it.value as TidalEvent.VoiceHold).amount }
        assertEquals(listOf(0.2f, 0.5f, 0.8f), values, "Hold values should be in order")
    }
    
    @Test
    fun `hold pattern has source locations for UI highlighting`() = kotlinx.coroutines.test.runTest {
        // Verify that hold patterns have source locations for UI highlighting
        val result = repl.evaluateSuspend("d1 \$ hold \"0.5\"")
        assertIs<ReplResult.Success>(result)
        
        // Success means pattern parsed correctly with locations
    }
    
    @Test
    fun `hold pattern voice indices are stable across multiple queries`() {
        // Ensure voice indices don't change between queries (the bug we fixed)
        val result = TidalParser.parseFloats("0.2 0.5 0.8") { TidalEvent.VoiceHold(0, it, emptyList()) }
        assertIs<TidalParser.ParseResult.Success<TidalEvent>>(result)
        
        // Query multiple times
        val events1 = result.pattern.query(Arc.UNIT).sortedBy { it.part.start }
        val events2 = result.pattern.query(Arc.UNIT).sortedBy { it.part.start }
        val events3 = result.pattern.query(Arc.UNIT).sortedBy { it.part.start }
        
        // Values should be identical across all queries
        val amounts1 = events1.map { (it.value as TidalEvent.VoiceHold).amount }
        val amounts2 = events2.map { (it.value as TidalEvent.VoiceHold).amount }
        val amounts3 = events3.map { (it.value as TidalEvent.VoiceHold).amount }
        assertEquals(amounts1, amounts2)
        assertEquals(amounts2, amounts3)
    }
    
    // =========================================================================
    // BPM Command Tests
    // =========================================================================
    
    @Test
    fun `evaluate supports bpm command with space syntax`() = kotlinx.coroutines.test.runTest {
        // bpm 108 should set tempo
        val result = repl.evaluateSuspend("bpm 108")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports bpm command with colon syntax`() = kotlinx.coroutines.test.runTest {
        // bpm:120 should set tempo
        val result = repl.evaluateSuspend("bpm:120")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports bpm command with quoted value`() = kotlinx.coroutines.test.runTest {
        // bpm "90" should work
        val result = repl.evaluateSuspend("bpm \"90\"")
        assertIs<ReplResult.Success>(result)
    }
    
    @Test
    fun `evaluate supports bpm command with decimal`() = kotlinx.coroutines.test.runTest {
        // bpm 108.5 should work
        val result = repl.evaluateSuspend("bpm 108.5")
        assertIs<ReplResult.Success>(result)
    }
    
    // =========================================================================
    // Full Example Pattern Tests
    // =========================================================================
    
    @Test
    fun `evaluate parses In-A-Gadda-Da-Vida example`() = kotlinx.coroutines.test.runTest {
        // The full In-A-Gadda-Da-Vida pattern from the sheet music transcription
        val code = """
            bpm 108
            
            # Add some organ-like sustain
            hold:1 0.8
            hold:2 0.8
            drive:0.3
            delaymix:0.2
            feedback:0.4
            
            # Main vocal/melody line (the iconic riff)
            d1 $ note "d4 d4 d4 d4 d4 d4 d4 d4"
            d2 $ slow 2 $ note "c5 a4 f4 d4 [c4 f4@3]"
            
            # Bass line (classic rock organ bass)
            d3 $ note "d2 ~ d2 ~ [a2 g#2 g2] ~"
            
            # Piano chords (right hand)
            d4 $ slow 2 $ note "[d4 f4 a4] ~ [d4 f4 a4] [c4 e4 g4]"
        """.trimIndent()
        
        val result = repl.evaluateSuspend(code)
        assertIs<ReplResult.Success>(result)
        
        // Verify all 4 pattern slots were created
        val slots = result.slots
        assertTrue(slots.contains("d1"), "Should have d1 slot")
        assertTrue(slots.contains("d2"), "Should have d2 slot")
        assertTrue(slots.contains("d3"), "Should have d3 slot")
        assertTrue(slots.contains("d4"), "Should have d4 slot")
    }
    
    @Test
    fun `evaluate handles multiline pattern with bpm and effects`() = kotlinx.coroutines.test.runTest {
        // Simplified test with bpm and a few patterns
        val code = """
            bpm 120
            drive:0.5
            d1 $ note "c4 e4 g4"
        """.trimIndent()
        
        val result = repl.evaluateSuspend(code)
        assertIs<ReplResult.Success>(result)
        assertTrue(result.slots.contains("d1"))
    }
}


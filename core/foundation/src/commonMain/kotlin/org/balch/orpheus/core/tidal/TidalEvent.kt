package org.balch.orpheus.core.tidal

/**
 * Source location for highlighting tokens when they trigger.
 * Character positions are relative to the full code string.
 */
data class SourceLocation(
    val start: Int,
    val end: Int
)

/**
 * Tidal events that control synth parameters.
 * Each event type maps to specific SynthController methods.
 * Events carry source locations for UI highlighting.
 */
sealed class TidalEvent {
    
    /**
     * Source locations where this event was defined in code.
     * Used for highlighting tokens when they trigger.
     */
    abstract val locations: List<SourceLocation>
    
    /**
     * Create a copy of this event with additional location.
     */
    abstract fun withLocation(location: SourceLocation): TidalEvent

    /**
     * Create a copy of this event with all locations shifted by an offset.
     */
    abstract fun shiftLocations(offset: Int): TidalEvent

    /**
     * Voice gate control - triggers voice envelope.
     * @param voiceIndex Voice index (0-7)
     * @param active Whether the gate is on or off
     */
    data class Gate(
        val voiceIndex: Int,
        val active: Boolean = true,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Melodic note event for playing specific pitches.
     * Uses the channel field for voice assignment, allowing melodic patterns
     * to play on a single voice with pitch changes via frequency automation.
     * 
     * @param midiNote MIDI note number (0-127, 60 = middle C)
     * @param velocity Note velocity (0.0-1.0)
     * @param duration Note duration in cycles (default 0.25 = 16th note)
     * @param channel Voice/channel for playback (0-7, default 0)
     */
    data class Note(
        val midiNote: Int,
        val velocity: Float = 1.0f,
        val duration: Float = 0.25f,
        val channel: Int = 0,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Sample playback event for drums and one-shots.
     * TODO: Implement sample player plugin for SynthEngine
     * 
     * @param name Sample name (e.g., "bd", "sn", "hh")
     * @param n Sample index/variation (0 = first sample)
     * @param gain Volume (0.0-1.0)
     */
    data class Sample(
        val name: String,
        val n: Int = 0,
        val gain: Float = 1.0f,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Voice tuning control.
     * @param voiceIndex Voice index (0-7)
     * @param tune Normalized tuning value (0.0-1.0, where 0.5 is middle)
     */
    data class VoiceTune(
        val voiceIndex: Int,
        val tune: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Voice hold/sustain level.
     * @param voiceIndex Voice index (0-7)
     * @param amount Hold level (0.0-1.0)
     */
    data class VoiceHold(
        val voiceIndex: Int,
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }

    /**
     * Voice envelope speed control.
     * @param voiceIndex Voice index (0-7)
     * @param speed Envelope speed (0.0=fast, 1.0=slow)
     */
    data class VoiceEnvSpeed(
        val voiceIndex: Int,
        val speed: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Quad pitch (groups of 4 voices).
     * @param quadIndex Quad index (0 or 1)
     * @param pitch Normalized pitch (0.0-1.0, 0.5 = unity)
     */
    data class QuadPitch(
        val quadIndex: Int,
        val pitch: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Quad hold level.
     * @param quadIndex Quad index (0 or 1)
     * @param amount Hold amount (0.0-1.0)
     */
    data class QuadHold(
        val quadIndex: Int,
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Delay time control.
     * @param delayIndex Delay index (0 or 1)
     * @param time Normalized time (0.0-1.0)
     */
    data class DelayTime(
        val delayIndex: Int,
        val time: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Delay feedback control.
     * @param amount Feedback amount (0.0-1.0)
     */
    data class DelayFeedback(
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Delay mix control.
     * @param amount Wet/dry mix (0.0-1.0)
     */
    data class DelayMix(
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * LFO frequency control.
     * @param lfoIndex LFO index (0 or 1)
     * @param frequency LFO frequency in Hz
     */
    data class LfoFreq(
        val lfoIndex: Int,
        val frequency: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Distortion drive control.
     * @param amount Drive amount (0.0-1.0)
     */
    data class Drive(
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Distortion mix control.
     * @param amount Distortion mix (0.0-1.0)
     */
    data class DistortionMix(
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Vibrato depth control.
     * @param amount Vibrato depth (0.0-1.0)
     */
    data class Vibrato(
        val amount: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Voice pan position.
     * @param voiceIndex Voice index (0-7)
     * @param pan Pan position (-1.0 left to 1.0 right)
     */
    data class VoicePan(
        val voiceIndex: Int,
        val pan: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Duo modulation source control.
     * @param duoIndex Duo index (0-3, where duo 0 = voices 0-1, duo 1 = voices 2-3, etc.)
     * @param source Modulation source: "fm" (voice FM), "off", or "lfo"
     */
    data class DuoMod(
        val duoIndex: Int,
        val source: String, // "fm", "off", "lfo"
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

    
    /**
     * Pair waveform sharpness control.
     * @param pairIndex Pair index (0-3, where pair 0 = voices 0-1, etc.)
     * @param sharpness Waveform sharpness (0.0 = triangle, 1.0 = square)
     */
    data class PairSharp(
        val pairIndex: Int,
        val sharpness: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }

    /**
     * Pair engine selection.
     * @param pairIndex Pair index (0-3, where pair 0 = voices 0-1, etc.)
     * @param engineId Engine ID: 0=osc, 5=fm, 6=noise, 7=wave, 8=va, 9=additive, 10=grain, 11=string, 12=modal
     */
    data class PairEngine(
        val pairIndex: Int,
        val engineId: Int,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }

    /**
     * Generic control event for any synthesis parameter.
     * @param controlId Internal ID of the control (e.g. "drum_bd_trigger")
     * @param value Normalized value (0.0-1.0)
     */
    data class Control(
        val controlId: String,
        val value: Float,
        override val locations: List<SourceLocation> = emptyList()
    ) : TidalEvent() {
        override fun withLocation(location: SourceLocation) = copy(locations = locations + location)
        override fun shiftLocations(offset: Int) = copy(locations = locations.map { SourceLocation(it.start + offset, it.end + offset) })
    }
    

}

/**
 * DSL builder functions for creating REPL patterns.
 */
object Orpheus {
    
    // === Voice Gate Patterns ===
    
    /**
     * Create a gate pattern for a single voice.
     */
    fun gate(voiceIndex: Int): Pattern<TidalEvent> = 
        Pattern.pure(TidalEvent.Gate(voiceIndex, true))
    
    /**
     * Create a pattern that gates multiple voices simultaneously.
     */
    fun gates(vararg voiceIndices: Int): Pattern<TidalEvent> =
        Pattern.stack(voiceIndices.map { gate(it) })
    
    /**
     * All 8 voices gate pattern.
     */
    fun allGates(): Pattern<TidalEvent> = gates(0, 1, 2, 3, 4, 5, 6, 7)
    
    /**
     * Quad 1 voices (0-3) gate pattern.
     */
    fun quad1Gates(): Pattern<TidalEvent> = gates(0, 1, 2, 3)
    
    /**
     * Quad 2 voices (4-7) gate pattern.
     */
    fun quad2Gates(): Pattern<TidalEvent> = gates(4, 5, 6, 7)
    
    // === Voice Tuning Patterns ===
    
    /**
     * Create a tuning pattern for a voice.
     */
    fun voiceTune(voiceIndex: Int, tune: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.VoiceTune(voiceIndex, tune))
    
    /**
     * Create a sequence of tune values for a voice.
     */
    fun voiceTuneSeq(voiceIndex: Int, vararg tunes: Float): Pattern<TidalEvent> =
        Pattern.fastcat(tunes.map { voiceTune(voiceIndex, it) })
    
    // === Voice Hold Patterns ===
    
    fun voiceHold(voiceIndex: Int, amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.VoiceHold(voiceIndex, amount))

    fun voiceEnvSpeed(voiceIndex: Int, speed: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.VoiceEnvSpeed(voiceIndex, speed))
    
    // === Quad Controls ===
    
    fun quadPitch(quadIndex: Int, pitch: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.QuadPitch(quadIndex, pitch))
    
    fun quadPitchSeq(quadIndex: Int, vararg pitches: Float): Pattern<TidalEvent> =
        Pattern.fastcat(pitches.map { quadPitch(quadIndex, it) })
    
    fun quadHold(quadIndex: Int, amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.QuadHold(quadIndex, amount))
    
    // === Delay Controls ===
    
    fun delayTime(delayIndex: Int, time: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.DelayTime(delayIndex, time))
    
    fun delayTimeSeq(delayIndex: Int, vararg times: Float): Pattern<TidalEvent> =
        Pattern.fastcat(times.map { delayTime(delayIndex, it) })
    
    fun delayFeedback(amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.DelayFeedback(amount))
    
    fun delayMix(amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.DelayMix(amount))
    
    // === LFO Controls ===
    
    fun lfoFreq(lfoIndex: Int, frequency: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.LfoFreq(lfoIndex, frequency))
    
    fun lfoFreqSeq(lfoIndex: Int, vararg frequencies: Float): Pattern<TidalEvent> =
        Pattern.fastcat(frequencies.map { lfoFreq(lfoIndex, it) })
    
    // === Distortion Controls ===
    
    fun drive(amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.Drive(amount))
    
    fun distortionMix(amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.DistortionMix(amount))
    
    // === Global Controls ===
    
    fun vibrato(amount: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.Vibrato(amount))
    
    fun voicePan(voiceIndex: Int, pan: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.VoicePan(voiceIndex, pan))

    // === Pair Engine Selection ===

    fun pairEngine(pairIndex: Int, engineId: Int): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.PairEngine(pairIndex, engineId))

    // === Generic Control ===
    
    fun control(id: String, value: Float): Pattern<TidalEvent> =
        Pattern.pure(TidalEvent.Control(id, value))

    /**
     * Create a sequence of control values.
     */
    fun controlSeq(id: String, vararg values: Float): Pattern<TidalEvent> =
        Pattern.fastcat(values.map { control(id, it) })

    // === Silence ===
    
    fun silence(): Pattern<TidalEvent> = Pattern.silence()
}

// Extension functions to add parameters to gate patterns
fun Pattern<TidalEvent>.withTune(voiceIndex: Int, tune: Float): Pattern<TidalEvent> =
    Pattern.stack(this, Orpheus.voiceTune(voiceIndex, tune))

fun Pattern<TidalEvent>.withHold(voiceIndex: Int, amount: Float): Pattern<TidalEvent> =
    Pattern.stack(this, Orpheus.voiceHold(voiceIndex, amount))

fun Pattern<TidalEvent>.withEnvSpeed(voiceIndex: Int, speed: Float): Pattern<TidalEvent> =
    Pattern.stack(this, Orpheus.voiceEnvSpeed(voiceIndex, speed))

fun Pattern<TidalEvent>.withDelay(time: Float, feedback: Float = 0.5f): Pattern<TidalEvent> =
    Pattern.stack(
        this,
        Orpheus.delayTime(0, time),
        Orpheus.delayTime(1, time),
        Orpheus.delayFeedback(feedback)
    )

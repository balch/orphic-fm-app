package org.balch.orpheus.core.tidal

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.util.Logger
import org.balch.orpheus.util.currentTimeMillis
import kotlin.math.pow

/**
 * State of the Tidal scheduler.
 */
data class TidalSchedulerState(
    val isPlaying: Boolean = false,
    val currentCycle: Int = 0,
    val cyclePosition: Double = 0.0,
    val bpm: Double = 120.0,
    val cps: Double = bpm / 60.0 / 4.0  // Cycles per second (4 beats per cycle by default)
)

/**
 * Real-time scheduler for Tidal patterns.
 * 
 * Manages pattern playback, timing, and event dispatch to the SynthEngine.
 * 
 * Uses `SynthEngine.setParameterAutomation` for sample-accurate scheduling of audio events, 
 * while using coroutines for synchronized UI highlighting.
 */
@SingleIn(AppScope::class)
@Inject
class TidalScheduler(
    private val synthController: SynthController,
    private val synthEngine: SynthEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _state = MutableStateFlow(TidalSchedulerState())
    val state: StateFlow<TidalSchedulerState> = _state.asStateFlow()
    
    /**
     * Trigger event for UI highlighting with source locations.
     */
    data class TriggerEvent(
        val voiceIndex: Int,
        val locations: List<SourceLocation>
    )
    
    // Emit trigger events for UI highlighting
    private val _triggers = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)
    val triggers: SharedFlow<TriggerEvent> = _triggers.asSharedFlow()
    
    private var playbackJob: Job? = null
    private var currentPattern: Pattern<TidalEvent>? = null
    
    // Scheduler timing parameters
    private val scheduleWindowMs = 250L  // Schedule 250ms chunks at a time
    private val lookaheadMs = 100L       // Look ahead into the pattern
    
    /**
     * Set the current pattern to play.
     */
    fun setPattern(pattern: Pattern<TidalEvent>) {
        currentPattern = pattern
        if (_state.value.isPlaying) {
            // If already playing, restart with new pattern
            // TODO: Hot-swap instead of restart to keep phase?
            // For now restart is safer to clear automation
            stop()
            play()
        }
    }
    
    /**
     * Start pattern playback.
     */
    fun play() {
        if (playbackJob?.isActive == true) return
        
        val pattern = currentPattern ?: return
        
        Logger.info { "TidalScheduler: Starting playback" }
        _state.value = _state.value.copy(isPlaying = true, currentCycle = 0, cyclePosition = 0.0)
        
        playbackJob = scope.launch {
            val startTime = currentTimeMillis()
            var nextScheduleTime = startTime
            
            // Initial silence to allow scheduling?
            // We want to start immediately.
            // We schedule the first window [0, windowMs].
            
            var lastScheduledSeconds = 0.0
            
            while (isActive) {
                val now = currentTimeMillis()
                
                // Wait until we are close to needing the next block
                // We want to be ahead of real time by a safe margin.
                val drift = now - nextScheduleTime
                if (drift < 0) {
                     delay(-drift)
                }
                nextScheduleTime += scheduleWindowMs
                
                // Calculate time window in seconds relative to start
                // Note: We use relative time for automation paths
                val windowStartSeconds = lastScheduledSeconds
                val windowEndSeconds = windowStartSeconds + (scheduleWindowMs / 1000.0)
                
                // Update UI state (approximate)
                val currentRealSeconds = (now - startTime).toDouble() / 1000.0
                val cycles = currentRealSeconds * _state.value.cps
                _state.value = _state.value.copy(
                    currentCycle = cycles.toInt(),
                    cyclePosition = cycles % 1.0
                )

                // Query events for this upcoming window
                val queryArc = Arc(
                    windowStartSeconds * _state.value.cps,
                    windowEndSeconds * _state.value.cps
                )
                
                // Get all events in the window
                val events = pattern.query(queryArc).filter { it.hasOnset() }
                
                // 1. Schedule Audio (Precise Automation)
                scheduleAudioEvents(events, windowStartSeconds, windowEndSeconds, startTime)
                
                // 2. Schedule Visuals (Coroutines)
                scheduleVisuals(events, startTime)
                
                lastScheduledSeconds = windowEndSeconds
            }
        }
    }
    
    /**
     * Stop pattern playback.
     */
    fun stop() {
        Logger.info { "TidalScheduler: Stopping playback" }
        playbackJob?.cancel()
        playbackJob = null
        
        // Clear automation and turn off all voices
        for (i in 0 until 12) {
            synthEngine.clearParameterAutomation("voice_gate_$i")
            synthEngine.clearParameterAutomation("voice_freq_$i")
            synthEngine.setVoiceGate(i, false)
        }
        
        _state.value = _state.value.copy(isPlaying = false)
    }
    
    /**
     * Set the tempo in BPM.
     */
    fun setBpm(bpm: Double) {
        val cps = bpm / 60.0 / 4.0
        _state.value = _state.value.copy(bpm = bpm, cps = cps)
    }
    
    private fun scheduleAudioEvents(
        events: List<Event<TidalEvent>>,
        windowStart: Double,
        windowEnd: Double,
        playbackStartTime: Long
    ) {
        val windowDuration = windowEnd - windowStart
        val cps = _state.value.cps
        
        // Group by Voice
        // Notes use their channel field for voice assignment (default 0)
        // This allows melodic patterns to play on a single voice with pitch changes
        val voiceEvents = events.mapNotNull { event ->
             when (val value = event.value) {
                 is TidalEvent.Note -> (8 + (value.channel % 4)) to event
                 is TidalEvent.Gate -> value.voiceIndex to event
                 is TidalEvent.Sample -> {
                     val idx = when (value.name) {
                         "bd", "kick" -> 8
                         "sn", "sd", "snare" -> 9
                         "hh", "hat", "oh" -> 10
                         "cp", "clap" -> 11
                         else -> 8 + (value.n % 4)
                     }
                     idx to event
                 }
                 else -> null
             }
        }.groupBy({ it.first }, { it.second })
        
        // Ensure silence for active voices if no new events?
        // Rely on previous automation completing naturally.
        
        // For each voice with events, build automation path
        voiceEvents.forEach { (voiceIndex, noteEvents) ->
            if (voiceIndex !in 0..11) return@forEach
            
            // Build Gate and Freq paths
            // Path needs to be relative to NOW (trigger time).
            // But we are scheduling for a window that starts at `windowStart`?
            // Actually, we are running this loop AHEAD of time.
            // If `nextScheduleTime` is when we woke up.
            // Ideally we want the automation to start exactly at `windowStart` relative to `playbackStartTime`.
            // But `setParameterAutomation` starts NOW.
            // So we must offset the path by `(windowStartAbsoluteTime - now)`.
            // `windowStartAbsoluteTime` = playbackStartTime + (windowStart * 1000)
            
            val now = currentTimeMillis()
            val windowStartAbs = playbackStartTime + (windowStart * 1000).toLong()
            val offsetMs = (windowStartAbs - now).coerceAtLeast(0) // Should be +ve if ahead
            val offsetSec = offsetMs / 1000.0
            
            // We use lists to build the path
            val gateTimes = mutableListOf<Float>()
            val gateValues = mutableListOf<Float>()
            val freqTimes = mutableListOf<Float>()
            val freqValues = mutableListOf<Float>()
            
            // Initial offset for alignment
            // If offset > 0, we hold previous value? 
            // Better to insert a point at 0 if needed, but linear ramp interpolates.
            // Tidal events are discrete.
            // We want step-like behavior for freq, pulse for gate.
            // Using Mode 0 (Linear) in automation player.
            // For step-like freq, we need two points per change: (t, old), (t, new).
            // Or just very steep ramp.
            
            // Add initial gate=0 point to align to window start
            // (Don't set freq to 0 - that causes pitch issues)
            if (offsetSec > 0) {
                 gateTimes.add(offsetSec.toFloat())
                 gateValues.add(0f) 
            }
            
            noteEvents.sortedBy { it.part.start }.forEach { event ->
                val eventStartSeconds = (event.part.start / cps) - windowStart
                val eventDurationSeconds = (event.part.end - event.part.start) / cps
                
                val tStart = (offsetSec + eventStartSeconds).toFloat()
                val tEnd = (tStart + eventDurationSeconds).toFloat()
                
                // Add Gate Pulse
                // 0 -> 1 at start
                gateTimes.add(tStart)
                gateValues.add(1f)
                
                // 1 -> 0 at end
                gateTimes.add(tEnd)
                gateValues.add(0f)
                
                // Add Freq Step with hold point
                if (event.value is TidalEvent.Note) {
                    val note = event.value as TidalEvent.Note
                    // Standard MIDI to frequency: 440Hz = A4 = MIDI 69
                    val freq = 440.0 * 2.0.pow((note.midiNote - 69) / 12.0)
                    
                    // Set freq at start AND hold at end (step-like behavior)
                    freqTimes.add(tStart)
                    freqValues.add(freq.toFloat())
                    
                    // Hold until note end - ensures the automation player
                    // outputs this frequency throughout the note duration
                    freqTimes.add(tEnd)
                    freqValues.add(freq.toFloat())
                } else if (event.value is TidalEvent.Sample) {
                    val sample = event.value as TidalEvent.Sample
                    // Lookup in Drum Library, default to 440Hz if not found
                    val patch = DrumDefs.LIBRARY[sample.name]
                    val freq = patch?.frequency?.toDouble() ?: 440.0
                    
                    freqTimes.add(tStart)
                    freqValues.add(freq.toFloat())
                    
                    freqTimes.add(tEnd)
                    freqValues.add(freq.toFloat())
                }
            }
            
            // Send Gate Automation
            if (gateTimes.isNotEmpty()) {
                synthEngine.setParameterAutomation(
                    "voice_gate_$voiceIndex",
                    gateTimes.toFloatArray(),
                    gateValues.toFloatArray(),
                    gateTimes.size,
                    gateTimes.last() + 0.1f, // padding
                    0 // Linear
                )
            }
            
            // Send Freq Automation
            if (freqTimes.isNotEmpty()) {
                org.balch.orpheus.util.Logger.info { 
                    "Note Freq: voice=$voiceIndex, times=${freqTimes.take(5)}, freqs=${freqValues.take(5)} Hz" 
                }
                synthEngine.setParameterAutomation(
                    "voice_freq_$voiceIndex",
                    freqTimes.toFloatArray(),
                    freqValues.toFloatArray(),
                    freqTimes.size,
                    freqTimes.last() + 0.1f,
                    0
                )
            }
        }
    }
    
    private fun scheduleVisuals(
        events: List<Event<TidalEvent>>,
        playbackStartTime: Long
    ) {
         val cps = _state.value.cps
         events.forEach { event ->
             val eventTimeCycles = event.part.start
             val eventTimeSeconds = eventTimeCycles / cps
             val eventTimeAbs = playbackStartTime + (eventTimeSeconds * 1000).toLong()
             val now = currentTimeMillis()
             val delayMs = eventTimeAbs - now
             
             if (delayMs > 0) {
                 scope.launch {
                     delay(delayMs)
                     triggerVisual(event.value)
                 }
             } else {
                 triggerVisual(event.value)
             }
         }
    }
    
    private fun triggerVisual(event: TidalEvent) {
         when (event) {
             is TidalEvent.Note -> {
                 // Map to REPL voices 8-11
                 val idx = 8 + (event.channel % 4)
                 _triggers.tryEmit(TriggerEvent(idx, event.locations))
             }
             is TidalEvent.Gate -> {
                 _triggers.tryEmit(TriggerEvent(event.voiceIndex, event.locations))
             }
             is TidalEvent.Sample -> {
                 // Map standard drum names to indices 8-11
                 val idx = when (event.name) {
                     "bd", "kick" -> 8
                     "sn", "sd", "snare" -> 9
                     "hh", "hat", "oh" -> 10
                     "cp", "clap" -> 11
                     "mt", "tom" -> 8
                     "lt" -> 9
                     "ht" -> 10
                     "cb", "cowbell" -> 11
                     else -> 8 + (event.n % 4)
                 }
                 
                 // Apply synthesis parameters from library
                 val patch = DrumDefs.LIBRARY[event.name]
                 if (patch != null) {
                     DrumDefs.apply(synthEngine, idx, patch)
                 }
                 
                 _triggers.tryEmit(TriggerEvent(idx, event.locations))
             }
             // Handle other events as immediate dispatch or similar?
             // Since we removed dispatchEvent loop, we need to handle non-scheduled params here too?
             // Ideally Params like Filter/Pan should also be scheduled via automation.
             // For now, let's just allow them to trigger "now" if they are in the window?
             // But simpler to just fire them.
             else -> dispatchEvent(event) // Legacy immediate dispatch for non-note params
         }
    }

    /**
     * Dispatch a Tidal event to the SynthController/Engine.
     */
    private fun dispatchEvent(event: TidalEvent) {
        when (event) {
            is TidalEvent.Note,
            is TidalEvent.Gate,
            is TidalEvent.Sample -> { /* Handled by scheduleAudioEvents */ }
            
            is TidalEvent.VoiceTune -> {
                synthController.emitControlChange(
                    "voice_${event.voiceIndex}_tune",
                    event.tune,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setVoiceTune(event.voiceIndex, event.tune)
            }
            
            is TidalEvent.VoiceHold -> {
                synthEngine.setVoiceHold(event.voiceIndex, event.amount)
            }
            
            is TidalEvent.QuadPitch -> {
                synthController.emitControlChange(
                    "quad_${event.quadIndex}_pitch",
                    event.pitch,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setQuadPitch(event.quadIndex, event.pitch)
            }
            
            is TidalEvent.QuadHold -> {
                synthEngine.setQuadHold(event.quadIndex, event.amount)
            }
            
            is TidalEvent.DelayTime -> {
                synthController.emitControlChange(
                    "delay_${event.delayIndex}_time",
                    event.time,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setDelayTime(event.delayIndex, event.time)
            }
            
            is TidalEvent.DelayFeedback -> {
                synthController.emitControlChange(
                    "delay_feedback",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setDelayFeedback(event.amount)
            }
            
            is TidalEvent.DelayMix -> {
                synthController.emitControlChange(
                    "delay_mix",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setDelayMix(event.amount)
            }
            
            is TidalEvent.LfoFreq -> {
                synthController.emitControlChange(
                    "lfo_${event.lfoIndex}_freq",
                    event.frequency,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setHyperLfoFreq(event.lfoIndex, event.frequency)
            }
            
            is TidalEvent.Drive -> {
                synthController.emitControlChange(
                    "drive",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setDrive(event.amount)
            }
            
            is TidalEvent.DistortionMix -> {
                synthController.emitControlChange(
                    "distortion_mix",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setDistortionMix(event.amount)
            }
            
            is TidalEvent.Vibrato -> {
                synthController.emitControlChange(
                    "vibrato",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setVibrato(event.amount)
            }
            
            is TidalEvent.VoicePan -> {
                synthEngine.setVoicePan(event.voiceIndex, event.pan)
            }
            
            is TidalEvent.MasterVolume -> {
                synthController.emitControlChange(
                    "master_volume",
                    event.volume,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setMasterVolume(event.volume)
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun dispose() {
        stop()
    }
}

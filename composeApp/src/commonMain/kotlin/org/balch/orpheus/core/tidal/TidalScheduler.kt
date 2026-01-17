package org.balch.orpheus.core.tidal

import com.diamondedge.logging.logging
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
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController

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
    private val synthEngine: SynthEngine,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val dispatchProvider: DispatcherProvider,
) {
    private val log = logging("TidalScheduler")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _state = MutableStateFlow(TidalSchedulerState())
    val state: StateFlow<TidalSchedulerState> = _state.asStateFlow()
    
    init {
        // Subscribe to playback lifecycle events (e.g., foreground service stop)
        scope.launch(dispatchProvider.default) {
            playbackLifecycleManager.events.collect { event ->
                when (event) {
                    is PlaybackLifecycleEvent.StopAll -> {
                        log.debug { "Received StopAll event - stopping scheduler" }
                        stop()
                    }
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }
    
    /**
     * Trigger event for UI highlighting with source locations.
     * @param voiceIndex The voice index for this trigger
     * @param locations Source locations to highlight in the code
     * @param durationMs How long to show the highlight (default 250ms, use longer for hold/sustain events)
     */
    data class TriggerEvent(
        val voiceIndex: Int,
        val locations: List<SourceLocation>,
        val durationMs: Long = 250L
    )
    
    // Emit trigger events for UI highlighting
    private val _triggers = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)
    val triggers: SharedFlow<TriggerEvent> = _triggers.asSharedFlow()
    
    private var playbackJob: Job? = null
    private var currentPattern: Pattern<TidalEvent>? = null
    
    // Scheduler timing parameters
    private val scheduleWindowSeconds = 0.25 // Schedule 250ms chunks at a time
    private val lookaheadSeconds = 0.1       // Look ahead into the pattern
    
    /**
     * Set the current pattern to play.
     */
    /**
     * Set the current pattern to play.
     */
    fun setPattern(pattern: Pattern<TidalEvent>) {
        currentPattern = pattern
        if (!_state.value.isPlaying) {
            play()
        }
    }
    
    /**
     * Start pattern playback.
     */
    fun play() {
        if (playbackJob?.isActive == true) return
        
        log.debug { "TidalScheduler: Starting playback" }
        
        // Request resume in case orchestrator was paused (muted)
        playbackLifecycleManager.tryRequestResume()
        
        _state.value = _state.value.copy(isPlaying = true, currentCycle = 0, cyclePosition = 0.0)
        
        playbackJob = scope.launch(dispatchProvider.default) {
            val startTime = synthEngine.getCurrentTime()
            var nextScheduleTime = startTime
            
            // Initial silence to allow scheduling?
            // We want to start immediately.
            // We schedule the first window [0, windowMs].
            
            var lastScheduledSeconds = 0.0
            
            while (isActive) {
                val now = synthEngine.getCurrentTime()
                
                // Wait until we are close to needing the next block
                // We want to be ahead of real time by a safe margin.
                val drift = now - nextScheduleTime
                if (drift < 0) {
                     delay((-drift * 1000).toLong())
                }
                nextScheduleTime += scheduleWindowSeconds
                
                // Calculate time window in seconds relative to start
                // Note: We use relative time for automation paths
                val windowStartSeconds = lastScheduledSeconds
                val windowEndSeconds = windowStartSeconds + scheduleWindowSeconds
                
                // Update UI state (approximate)
                val currentRealSeconds = (now - startTime)
                val cycles = currentRealSeconds * _state.value.cps
                _state.value = _state.value.copy(
                    currentCycle = cycles.toInt(),
                    cyclePosition = cycles % 1.0
                )

                // Query events for this upcoming window
                // Use currentPattern dynamically to allow hot-swapping
                val activePattern = currentPattern
                if (activePattern != null) {
                    val queryArc = Arc(
                        windowStartSeconds * _state.value.cps,
                        windowEndSeconds * _state.value.cps
                    )
                    
                    // Get all events in the window
                    val events = activePattern.query(queryArc).filter { it.hasOnset() }
                    
                    // 1. Schedule Audio (Precise Automation)
                    scheduleAudioEvents(events, windowStartSeconds, windowEndSeconds, startTime)
                    
                    // 2. Schedule Visuals (Coroutines)
                    scheduleVisuals(events, startTime)
                }
                
                lastScheduledSeconds = windowEndSeconds
            }
        }
    }

    
    /**
     * Stop pattern playback.
     */
    fun stop() {
        log.debug { "TidalScheduler: Stopping playback" }
        playbackJob?.cancel()
        playbackJob = null
        
        // Clear automation and turn off all voices
        for (i in 0 until 12) {
            synthEngine.clearParameterAutomation("voice_gate_$i")
            synthEngine.clearParameterAutomation("voice_freq_$i")
            synthEngine.setVoiceGate(i, false)
        }
        
        // Reset all holds to 0 for the 8 main voices
        for (i in 0 until 8) {
            synthEngine.setVoiceHold(i, 0f)
            synthController.emitControlChange("voice_${i}_hold", 0f, ControlEventOrigin.TIDAL)
        }
        
        // Reset quad holds
        for (i in 0 until 3) {
            synthEngine.setQuadHold(i, 0f)
            synthController.emitControlChange("quad_${i}_hold", 0f, ControlEventOrigin.TIDAL)
        }
        
        // Reset global effects to prevent "static tone"
        synthEngine.setDrive(0f)
        synthController.emitControlChange("drive", 0f, ControlEventOrigin.TIDAL)
        
        synthEngine.setDistortionMix(0f)
        synthController.emitControlChange("distortion_mix", 0f, ControlEventOrigin.TIDAL)
        
        synthEngine.setVibrato(0f)
        synthController.emitControlChange("vibrato", 0f, ControlEventOrigin.TIDAL)
        
        synthEngine.setDelayFeedback(0f)
        synthController.emitControlChange("delay_feedback", 0f, ControlEventOrigin.TIDAL)
        
        synthEngine.setDelayMix(0f)
        synthController.emitControlChange("delay_mix", 0f, ControlEventOrigin.TIDAL)
        
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
        playbackStartTime: Double
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
            
            val now = synthEngine.getCurrentTime() // Audio time
            val windowStartAbs = playbackStartTime + windowStart
            val offsetSec = (windowStartAbs - now).coerceAtLeast(0.0)

            
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
                log.debug { 
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
        playbackStartTime: Double
    ) {
         val cps = _state.value.cps
         events.forEach { event ->
             val eventTimeCycles = event.part.start
             val eventTimeSeconds = eventTimeCycles / cps
             val eventTimeAbs = playbackStartTime + eventTimeSeconds
             val now = synthEngine.getCurrentTime()
             val delayMs = ((eventTimeAbs - now) * 1000).toLong()
             
             if (delayMs > 0) {
                 scope.launch(dispatchProvider.default) {
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
             // Voice-level synth control events - emit trigger for highlighting
             is TidalEvent.VoiceHold -> {
                 if (event.locations.isNotEmpty()) {
                     // Hold events stay highlighted for the full cycle
                     val cycleDurationMs = (1000.0 / _state.value.cps).toLong().coerceIn(500L, 5000L)
                     _triggers.tryEmit(TriggerEvent(event.voiceIndex, event.locations, cycleDurationMs))
                 }
                 dispatchEvent(event)
             }
             is TidalEvent.VoiceTune -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.voiceIndex, event.locations))
                 }
                 dispatchEvent(event)
             }
             is TidalEvent.VoicePan -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.voiceIndex, event.locations))
                 }
                 dispatchEvent(event)
             }
             is TidalEvent.VoiceEnvSpeed -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.voiceIndex, event.locations))
                 }
                 dispatchEvent(event)
             }
             // Quad-level events - use quad index * 4 as representative voice
             is TidalEvent.QuadHold -> {
                 if (event.locations.isNotEmpty()) {
                     // Hold events stay highlighted for the full cycle
                     val cycleDurationMs = (1000.0 / _state.value.cps).toLong().coerceIn(500L, 5000L)
                     _triggers.tryEmit(TriggerEvent(event.quadIndex * 4, event.locations, cycleDurationMs))
                 }
                 dispatchEvent(event)
             }
             is TidalEvent.QuadPitch -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.quadIndex * 4, event.locations))
                 }
                 dispatchEvent(event)
             }
             // Pair/Duo level events
             is TidalEvent.PairSharp -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.pairIndex * 2, event.locations))
                 }
                 dispatchEvent(event)
             }
             is TidalEvent.DuoMod -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(event.duoIndex * 2, event.locations))
                 }
                 dispatchEvent(event)
             }
             // Global effects - use voice 0 as representative
             is TidalEvent.Drive,
             is TidalEvent.DistortionMix,
             is TidalEvent.Vibrato,
             is TidalEvent.DelayFeedback,
             is TidalEvent.DelayMix -> {
                 if (event.locations.isNotEmpty()) {
                     _triggers.tryEmit(TriggerEvent(0, event.locations))
                 }
                 dispatchEvent(event)
             }
             // Other events - just dispatch without trigger
             else -> dispatchEvent(event)
         }
    }

    /**
     * Dispatch a Tidal event immediately (public API for "once" commands).
     * Use this for bare control commands that should be applied immediately
     * without being scheduled as a cyclic pattern.
     */
    fun dispatchEventImmediate(event: TidalEvent) {
        log.debug { "Dispatching immediate event: $event" }
        dispatchEvent(event)
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
                synthController.emitControlChange(
                    "voice_${event.voiceIndex}_hold",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
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
                synthController.emitControlChange(
                    "quad_${event.quadIndex}_hold",
                    event.amount,
                    ControlEventOrigin.TIDAL
                )
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
            
            is TidalEvent.VoiceEnvSpeed -> {
                synthController.emitControlChange(
                    "voice_${event.voiceIndex}_env_speed",
                    event.speed,
                    ControlEventOrigin.TIDAL
                )
                synthEngine.setVoiceEnvelopeSpeed(event.voiceIndex, event.speed)
            }
            
            is TidalEvent.DuoMod -> {
                val modSource = when (event.source.lowercase()) {
                    "fm" -> org.balch.orpheus.core.audio.ModSource.VOICE_FM
                    "lfo" -> org.balch.orpheus.core.audio.ModSource.LFO
                    else -> org.balch.orpheus.core.audio.ModSource.OFF
                }
                synthEngine.setDuoModSource(event.duoIndex, modSource)
            }
            
            is TidalEvent.PairSharp -> {
                synthEngine.setPairSharpness(event.pairIndex, event.sharpness)
            }
            
            is TidalEvent.Control -> {
                synthController.emitControlChange(
                    event.controlId,
                    event.value,
                    ControlEventOrigin.TIDAL
                )
                // We don't call engine directly here as we don't know the mapping,
                // the ViewModels or SynthControllerPlugins will handle it.
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

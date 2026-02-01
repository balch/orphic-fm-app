package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * WASM actual implementation of AudioEngine using Web Audio API.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OrpheusAudioEngine @Inject constructor() : AudioEngine {
    private val audioContext: AudioContext = createAudioContext()
    
    // Create a channel splitter to route to left/right outputs
    private val splitter = audioContext.createChannelSplitter(2)
    
    // Create gain nodes as proxies for stereo output
    private val leftGain = audioContext.createGain().also { it.gain.value = 1.0f }
    private val rightGain = audioContext.createGain().also { it.gain.value = 1.0f }
    
    // Track all units for management
    private val units = mutableListOf<AudioUnit>()
    
    // CPU load estimation
    private var estimatedCpuLoad = 0f
    private var lastCpuUpdateTime = 0.0
    
    init {
        // Connect gain nodes to destination via a merger
        val merger = audioContext.createChannelMerger(2)
        leftGain.connect(merger, 0, 0)
        rightGain.connect(merger, 0, 1)
        merger.connect(audioContext.destination)
        
        // Sync with page background
        try {
            OrphicFM.syncNode(merger)
        } catch (e: Throwable) {
            // Ignore if OrphicFM is not available (e.g. standalone app)
        }
    }
    
    override fun start() {
        audioContext.resume()
    }
    
    override fun stop() {
        audioContext.suspend()
    }
    
    override val isRunning: Boolean
        get() = audioContext.state == "running"
    
    override val sampleRate: Int
        get() = audioContext.sampleRate.toInt()
    
    override fun addUnit(unit: AudioUnit) {
        units.add(unit)
        // Web Audio units are automatically part of the graph when connected
        // No explicit "add" needed like JSyn
    }
    

    
    override val lineOutLeft: AudioInput
        get() = WebAudioNodeInput(leftGain, 0, audioContext)
    
    override val lineOutRight: AudioInput
        get() = WebAudioNodeInput(rightGain, 0, audioContext)
    
    override fun getCpuLoad(): Float {
        // Web Audio doesn't expose CPU load directly, so we estimate it
        // based on the number of active units and complexity
        
        // Update estimation periodically (not every call for performance)
        val currentTime = audioContext.currentTime
        if (currentTime - lastCpuUpdateTime > 0.5) { // Update every 500ms
            lastCpuUpdateTime = currentTime
            
            // Estimate based on unit count and types
            // This is a rough approximation:
            // - Basic units (gain, passthrough): 0.5% each
            // - Oscillators: 1% each
            // - Filters/Effects: 2% each
            // - Delays: 1.5% each
            // - Analysis (peak followers): 1% each
            
            var estimatedLoad = 0f
            units.forEach { unit ->
                estimatedLoad += when (unit) {
                    is WebAudioSineOscillator, 
                    is WebAudioTriangleOscillator, 
                    is WebAudioSquareOscillator,
                    is WebAudioSawtoothOscillator -> 1.0f
                    is WebAudioDelayLine -> 1.5f
                    is WebAudioLimiter -> 2.0f
                    is WebAudioPeakFollower -> 1.0f
                    is WebAudioEnvelope -> 1.5f
                    is WebAudioMinimum, 
                    is WebAudioMaximum -> 1.5f // Complex waveshaping
                    else -> 0.5f // Basic units
                }
            }
            
            // Apply a scaling factor and cap at 100%
            // Assume ~50 units = ~50% load (rough baseline)
            estimatedCpuLoad = (estimatedLoad * 0.8f).coerceIn(0f, 100f)
        }
        
        return estimatedCpuLoad
    }

    override fun getCurrentTime(): Double = audioContext.currentTime
    
    /** Get the AudioContext for direct Web Audio API access */
    val webAudioContext: AudioContext get() = audioContext
}

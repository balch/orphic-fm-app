package org.balch.songe.core.audio.dsp

/**
 * WASM actual implementation of AudioEngine using Web Audio API.
 */
actual class AudioEngine actual constructor() {
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
    }
    
    actual fun start() {
        audioContext.resume()
    }
    
    actual fun stop() {
        audioContext.suspend()
    }
    
    actual val isRunning: Boolean
        get() = audioContext.state == "running"
    
    actual val sampleRate: Int
        get() = audioContext.sampleRate.toInt()
    
    actual fun addUnit(unit: AudioUnit) {
        units.add(unit)
        // Web Audio units are automatically part of the graph when connected
        // No explicit "add" needed like JSyn
    }
    
    // Unit Factories
    actual fun createSineOscillator(): SineOscillator = WebAudioSineOscillator(audioContext)
    actual fun createTriangleOscillator(): TriangleOscillator = WebAudioTriangleOscillator(audioContext)
    actual fun createSquareOscillator(): SquareOscillator = WebAudioSquareOscillator(audioContext)
    actual fun createEnvelope(): Envelope = WebAudioEnvelope(audioContext)
    actual fun createDelayLine(): DelayLine = WebAudioDelayLine(audioContext)
    actual fun createPeakFollower(): PeakFollower = WebAudioPeakFollower(audioContext)
    actual fun createLimiter(): Limiter = WebAudioLimiter(audioContext)
    actual fun createMultiply(): Multiply = WebAudioMultiply(audioContext)
    actual fun createAdd(): Add = WebAudioAdd(audioContext)
    actual fun createMultiplyAdd(): MultiplyAdd = WebAudioMultiplyAdd(audioContext)
    actual fun createPassThrough(): PassThrough = WebAudioPassThrough(audioContext)
    actual fun createMinimum(): Minimum = WebAudioMinimum(audioContext)
    actual fun createMaximum(): Maximum = WebAudioMaximum(audioContext)
    
    actual val lineOutLeft: AudioInput
        get() = WebAudioNodeInput(leftGain, 0, audioContext)
    
    actual val lineOutRight: AudioInput
        get() = WebAudioNodeInput(rightGain, 0, audioContext)
    
    actual fun getCpuLoad(): Float {
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
                    is WebAudioSquareOscillator -> 1.0f
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
    
    /** Get the AudioContext for direct Web Audio API access */
    val webAudioContext: AudioContext get() = audioContext
}

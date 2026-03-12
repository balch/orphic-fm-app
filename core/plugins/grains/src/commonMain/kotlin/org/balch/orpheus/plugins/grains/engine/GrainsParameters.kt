package org.balch.orpheus.plugins.grains.engine

/**
 * Processing modes matching MI Clouds PlaybackMode ordinals.
 *
 * GRANULAR: Overlapping grains with random/deterministic playback
 * STRETCH: Time-stretching (C++ only; Kotlin maps to granular player)
 * LOOPING_DELAY: Delay line when not frozen, looped buffer when frozen
 * SPECTRAL: FFT-based spectral processing (C++ only; Kotlin maps to shimmer player)
 */
enum class GrainsMode(val displayName: String) {
    GRANULAR("Gran"),       // 0
    STRETCH("Stretch"),     // 1
    LOOPING_DELAY("Loop"),  // 2
    SPECTRAL("Spectral"),   // 3
}

class GrainsParameters {
    // Target values (set from UI)
    var position: Float = 0.5f    // Center of buffer
    var size: Float = 0.5f        // Medium grain/loop size
    var pitch: Float = 0.5f       // No transpose (center = 1x speed)
    var density: Float = 0.5f     // Dead zone (no grains by default)
    var texture: Float = 0.5f     // Mid window shape
    var dryWet: Float = 0.5f      // 50% wet
    var stereoSpread: Float = 0f
    var feedback: Float = 0f
    var reverb: Float = 0f
    var freeze: Boolean = false
    var trigger: Boolean = false
    var gate: Boolean = false
    var mode: GrainsMode = GrainsMode.GRANULAR

    // Smoothed internal values (avoid clicks)
    private var smoothPosition: Float = 0.5f
    private var smoothSize: Float = 0.5f
    private var smoothPitch: Float = 0.5f
    private var smoothDensity: Float = 0.5f
    private var smoothTexture: Float = 0.5f
    private var smoothDryWet: Float = 0.5f
    
    // Track if smoothed values have been initialized
    private var initialized = false
    
    private val smoothCoeff = 0.1f // 10% smoothing per block
    
    fun updateSmoothing() {
        // On first call, snap to target values (no smoothing)
        if (!initialized) {
            smoothPosition = position
            smoothSize = size
            smoothPitch = pitch
            smoothDensity = density
            smoothTexture = texture
            smoothDryWet = dryWet
            initialized = true
            return
        }
        
        smoothPosition += (position - smoothPosition) * smoothCoeff
        smoothSize += (size - smoothSize) * smoothCoeff
        smoothPitch += (pitch - smoothPitch) * smoothCoeff
        smoothDensity += (density - smoothDensity) * smoothCoeff
        smoothTexture += (texture - smoothTexture) * smoothCoeff
        smoothDryWet += (dryWet - smoothDryWet) * smoothCoeff
    }
    
    fun smoothedPosition() = smoothPosition
    fun smoothedSize() = smoothSize
    fun smoothedPitch() = smoothPitch
    fun smoothedDensity() = smoothDensity
    fun smoothedTexture() = smoothTexture
    fun smoothedDryWet() = smoothDryWet
}

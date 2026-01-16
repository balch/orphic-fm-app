package org.balch.orpheus.core.audio.dsp.synth.grains

enum class GrainsMode(val displayName: String) {
    GRANULAR("Gran"),
    REVERSE("Rev"),
    SHIMMER("Shim"),
}

class GrainsParameters {
    // Target values (set from UI)
    var position: Float = 0f
    var size: Float = 0f
    var pitch: Float = 0f
    var density: Float = 0f
    var texture: Float = 0f
    var dryWet: Float = 0f
    var stereoSpread: Float = 0f
    var feedback: Float = 0f
    var reverb: Float = 0f
    var freeze: Boolean = false
    var trigger: Boolean = false
    var gate: Boolean = false
    var mode: GrainsMode = GrainsMode.GRANULAR

    // Smoothed internal values (avoid clicks)
    private var smoothPosition: Float = 0f
    private var smoothSize: Float = 0f
    private var smoothPitch: Float = 0f
    private var smoothDensity: Float = 0f
    private var smoothTexture: Float = 0f
    private var smoothDryWet: Float = 0f
    
    private val smoothCoeff = 0.1f // 10% smoothing per block
    
    fun updateSmoothing() {
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

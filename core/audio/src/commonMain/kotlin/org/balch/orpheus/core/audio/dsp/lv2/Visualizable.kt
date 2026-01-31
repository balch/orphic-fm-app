package org.balch.orpheus.core.audio.dsp.lv2

// Note: Requires Compose dependency if we return Composables directly.
// For now, we will return a generic data identifier or keeping this interface abstract
// until we decide if core:audio allows Compose. 
// Ideally, core:audio is pure Kotlin/logic and UI is separate.
// But for "Visualizable", it implies UI.
// We will define a marker interface for now.

interface Visualizable {
    /**
     * Type of visualization this plugin supports.
     * e.g. "scope", "meter", "spectrum", "custom"
     */
    val visualizationType: String
    
    /**
     * Get real-time data for visualization.
     * Returns a snapshot of data (e.g. waveform buffer).
     */
    fun getVisualizationData(): Any?
}

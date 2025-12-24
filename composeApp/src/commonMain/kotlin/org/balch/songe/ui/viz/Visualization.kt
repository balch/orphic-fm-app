package org.balch.songe.ui.viz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Configuration for liquid glassmorphism effects per visualization.
 * Each visualization can define its own settings to adjust how UI panels look.
 */
data class VisualizationLiquidEffects(
    val frostSmall: Float = 5f,      // Frost blur for small panels (dp)
    val frostMedium: Float = 7f,     // Frost blur for medium panels (dp)
    val frostLarge: Float = 9f,      // Frost blur for large panels (dp)
    val tintAlpha: Float = 0.12f,    // Tint overlay alpha
    val saturation: Float = 0.65f,   // Color saturation (<1 desaturate, >1 saturate)
    val contrast: Float = 0.75f      // Contrast adjustment
) {
    companion object {
        /** Default effects - moderate translucency */
        val Default = VisualizationLiquidEffects()
        
        /** Low effects for "Off" mode - less saturation, more frost */
        val Off = VisualizationLiquidEffects(
            frostSmall = 8f, frostMedium = 10f, frostLarge = 12f,
            saturation = 0.4f, contrast = 0.7f
        )
    }
}

/**
 * Interface for pluggable background visualizations.
 * Implementations should be injected into the set of available visualizations.
 */
interface Visualization {
    val id: String
    val name: String
    val color: Color
    val knob1Label: String
    val knob2Label: String
    
    /** Liquid glassmorphism effects for this visualization */
    val liquidEffects: VisualizationLiquidEffects get() = VisualizationLiquidEffects.Default
    
    fun setKnob1(value: Float)
    fun setKnob2(value: Float)
    
    fun onActivate() // Start loops/flows
    fun onDeactivate() // Stop loops/flow
    
    @Composable
    fun Content(modifier: Modifier)
}


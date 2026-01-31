package org.balch.orpheus.ui.viz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects

/**
 * Interface for pluggable background visualizations.
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

/**
 * Interface for visualizations that update their liquid effects dynamically.
 */
interface DynamicVisualization : Visualization {
    val liquidEffectsFlow: Flow<VisualizationLiquidEffects>
}

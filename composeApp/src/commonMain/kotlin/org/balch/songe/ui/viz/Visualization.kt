package org.balch.songe.ui.viz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
    
    fun setKnob1(value: Float)
    fun setKnob2(value: Float)
    
    fun onActivate() // Start loops/flows
    fun onDeactivate() // Stop loops/flow
    
    @Composable
    fun Content(modifier: Modifier)
}

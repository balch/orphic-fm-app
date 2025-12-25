package org.balch.songe.ui.viz

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidScope
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import kotlinx.coroutines.flow.Flow


data class VisualizationLiquidScope(
    val refraction: Float = 0f,
    val curve: Float = 0f,
    val edge: Float = 0f,
    val saturation: Float = .65f,
    val dispersion: Float = 0f,
    val contrast: Float = 0.75f,
)

/**
 * Configuration for liquid glassmorphism effects per visualization.
 * Each visualization can define its own settings to adjust how UI panels look.
 */
data class VisualizationLiquidEffects(
    val frostSmall: Float = 5f,      // Frost blur for small panels (dp)
    val frostMedium: Float = 7f,     // Frost blur for medium panels (dp)
    val frostLarge: Float = 9f,      // Frost blur for large panels (dp)
    val tintAlpha: Float = 0.12f,
    val top: VisualizationLiquidScope = VisualizationLiquidScope(),
    val bottom: VisualizationLiquidScope = VisualizationLiquidScope(),
) {
    companion object {
        /** Default effects - moderate translucency */
        val Default = VisualizationLiquidEffects()

        private val offLiquidScope = VisualizationLiquidScope(saturation = 0.4f, contrast = 0.7f)

        /** Low effects for "Off" mode - less saturation, more frost */
        val Off = VisualizationLiquidEffects(
            frostSmall = 8f,
            frostMedium = 10f,
            frostLarge = 12f,
            top = offLiquidScope,
            bottom = offLiquidScope,
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

/**
 * Interface for visualizations that update their liquid effects dynamically.
 */
interface DynamicVisualization : Visualization {
    val liquidEffectsFlow: Flow<VisualizationLiquidEffects>
}

/**
 * Applies liquid glassmorphism effect or falls back to solid background.
 * 
 * @param liquidState The LiquidState from composition local (null if not available)
 * @param scope The liquid scope settings (saturation, contrast, etc.)
 * @param frostAmount Amount of frost blur (dp)
 * @param color Tint color for the liquid effect
 * @param tintAlpha Alpha value for the tint color
 * @param shape Shape for clipping and liquid effect
 * @param block Optional additional configuration for the liquid scope
 */
fun Modifier.liquidVizEffects(
    liquidState: LiquidState?,
    scope: VisualizationLiquidScope,
    frostAmount: Dp,
    color: Color,
    tintAlpha: Float = 0.12f,
    shape: Shape = RoundedCornerShape(8.dp),
    block: LiquidScope.() -> Unit = {},
): Modifier {
    val baseModifier = this.clip(shape)
    
    return if (liquidState != null) {
        baseModifier.liquid(liquidState) {
            frost = frostAmount
            this.shape = shape
            tint = color.copy(alpha = tintAlpha)
            saturation = scope.saturation
            contrast = scope.contrast
            edge = scope.edge
            dispersion = scope.dispersion
            refraction = scope.refraction
            curve = scope.curve
            block()
        }
    } else {
        baseModifier.background(color.copy(alpha = 0.8f))
    }
}



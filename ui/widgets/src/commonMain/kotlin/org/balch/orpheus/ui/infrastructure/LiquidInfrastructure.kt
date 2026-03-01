package org.balch.orpheus.ui.infrastructure

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidScope
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Settings for a liquid effect scope (saturation, contrast, etc.)
 * @param refraction Controls how much the background distorts through the lens. Setting this to 0 removes the liquid
 * effect altogether, nullifying any [curve] value.
 ** @param curve  Adjusts how strongly the lens curves at its center vs. edges. Setting this to 0 removes the liquid
 * effect altogether, nullifying any [refraction] value.
 * @param edge Width of the rim lighting around the effect's edge. Higher values create a wider, softer edge and expand the region where rim lighting is applied.
 * Set to `0f` to disable this effect.
 * @param saturation Adjusts the color saturation of the content behind the liquid effect. Values greater than 1f create more vivid colors, while values less than 1f
 * decrease create more muted colors. A value of 0f results in grayscale.
 * @param dispersion Controls the chromatic aberration effect, which separates RGB channels to simulate
 * light dispersion through a lens. Higher values create more pronounced color separation, similar to light passing through
 * a prism. Set to 0f to disable chromatic aberration.
 * @param contrast Adjusts the contrast of the content behind the liquid effect. Values greater than 1f increase the difference between light and dark areas,
 * while values less than 1f reduce this difference.
 */
data class VisualizationLiquidScope(
    val refraction: Float = 0f,
    val curve: Float = 0f,
    val edge: Float = 0f,
    val saturation: Float = .65f,
    val dispersion: Float = 0f,
    val contrast: Float = 1f,
)

/**
 * Styling for center panels (titles, borders, etc.)
 */
data class CenterPanelStyle(
    val scope: VisualizationLiquidScope = VisualizationLiquidScope(contrast = 1.3f, saturation = 0.9f),
    val titleSize: TextUnit = 22.sp,
    val titleColor: Color = OrpheusColors.neonCyanBright,
    val borderColor: Color = Color.White.copy(alpha = 0.3f),
    val borderWidth: Dp = 1.dp,
    val titleElevation: Dp = 8.dp
)

/**
 * Configuration for liquid glassmorphism effects per visualization.
 */
data class VisualizationLiquidEffects(
    val frostSmall: Float = 4f,
    val frostMedium: Float = 6f,
    val frostLarge: Float = 8f,
    val tintAlpha: Float = 0.12f,
    val top: VisualizationLiquidScope = VisualizationLiquidScope(),
    val bottom: VisualizationLiquidScope = VisualizationLiquidScope(),
    val title: CenterPanelStyle = CenterPanelStyle(),
) {
    companion object {
        val Default = VisualizationLiquidEffects()
        
        val Off = VisualizationLiquidEffects(
            frostSmall = 8f,
            frostMedium = 10f,
            frostLarge = 12f,
            top = VisualizationLiquidScope(saturation = 0.4f, contrast = 0.7f),
            bottom = VisualizationLiquidScope(saturation = 0.4f, contrast = 0.7f),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(saturation = 0.4f, contrast = 0.7f),
                titleColor = Color.White.copy(alpha = 0.3f),
                borderColor = Color.White.copy(alpha = 0.05f)
            )
        )
    }
}

/**
 * CompositionLocal for sharing LiquidState across panels.
 */
val LocalLiquidState = compositionLocalOf<LiquidState?> { null }

/**
 * CompositionLocal for sharing LiquidState across dialog.
 */
val LocalDialogLiquidState = compositionLocalOf<LiquidState?> { null }

/**
 * CompositionLocal for sharing visualization-specific liquid effects across panels.
 */
val LocalLiquidEffects = compositionLocalOf { VisualizationLiquidEffects.Default }

/**
 * Applies liquid glassmorphism effect or falls back to solid background.
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
        baseModifier.background(color.copy(alpha = tintAlpha))
    }
}

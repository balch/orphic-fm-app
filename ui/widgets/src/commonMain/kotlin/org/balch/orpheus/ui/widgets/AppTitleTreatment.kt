package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

// On a dark theme a "raised" look reads through bevel + gradient, not Material elevation
// (drop shadows are near-invisible on black): a lit cyan TOP edge + dark BOTTOM edge, a
// top→bottom convex fill, and a soft cyan glow. Colors are compile-time constants, so these
// brushes are hoisted to file-level vals to avoid re-allocating on every recomposition.
private val raisedFill = Brush.verticalGradient(
    listOf(
        OrpheusColors.cosmicPurple.copy(alpha = 0.70f), // lit convex top
        OrpheusColors.deepPurple.copy(alpha = 0.96f),   // shadowed bottom
    )
)
private val raisedBevel = BorderStroke(
    1.5.dp,
    Brush.verticalGradient(
        listOf(
            OrpheusColors.neonCyan.copy(alpha = 0.95f), // top highlight (light from above)
            Color.Black.copy(alpha = 0.55f),            // bottom shadow edge
        )
    )
)

@Composable
fun AppTitleTreatment(
    modifier: Modifier = Modifier,
    title: String = AppConfig.APP_DISPLAY_NAME,
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects.Default,
    showSizeEffects: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 8.dp,
    /** When non-null, the title becomes a clickable, raised button (tap → [onClick]). */
    onClick: (() -> Unit)? = null,
) {
    val liquidState = LocalLiquidState.current
    val shape = RoundedCornerShape(8.dp)
    val raised = onClick != null
    val density = LocalDensity.current
    val textShadow = remember(effects.title.titleElevation, density) {
        val blur = with(density) { effects.title.titleElevation.toPx() }
        if (blur > 0f) {
            Shadow(
                color = Color.Black.copy(alpha = 0.5f),
                offset = Offset(0f, blur / 2),
                blurRadius = blur
            )
        } else {
            Shadow.None
        }
    }

    Card(
        modifier = modifier
            .then(
                if (raised) {
                    Modifier
                        .shadow(
                            elevation = 6.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = OrpheusColors.neonCyan,
                            spotColor = OrpheusColors.neonCyan,
                        )
                        .background(raisedFill, shape)
                } else {
                    Modifier.liquidVizEffects(
                        liquidState = liquidState,
                        scope = effects.title.scope,
                        frostAmount = effects.frostLarge.dp,
                        color = OrpheusColors.softPurple,
                        tintAlpha = 0.2f,
                        shape = shape,
                    )
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (raised) 0.dp else effects.title.titleElevation,
        ),
        shape = shape,
        border = when {
            raised -> raisedBevel
            showSizeEffects -> BorderStroke(effects.title.borderWidth, effects.title.borderColor)
            else -> null
        },
    ) {
        Text(
            text = title,
            fontSize =
                if (showSizeEffects) {
                    effects.title.titleSize
                } else 18.sp,
            fontWeight = FontWeight.Bold,
            color = effects.title.titleColor,
            // Explicit style= replaces LocalTextStyle rather than merging with it, so this
            // Text does not inherit OrpheusTheme's bodyLarge line box. Carry the theme's
            // metrics forward by hand if this ever needs to wrap to a second line.
            style = TextStyle(shadow = textShadow),
            modifier = Modifier
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)

        )
    }
}

@Preview
@Preview(name = "140%", fontScale = 1.4f)
@Composable
private fun AppTitleTreatmentPreview() {
    OrpheusTheme {
        AppTitleTreatment()
    }
}

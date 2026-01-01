package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun AppTitleTreatment(
    modifier: Modifier = Modifier,
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects.Default,
    showSizeEffects: Boolean = true,
) {
    val liquidState = LocalLiquidState.current
    val shape = RoundedCornerShape(8.dp)
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
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.title.scope,
                frostAmount = effects.frostLarge.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = 0.2f,
                shape = shape,
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = effects.title.titleElevation),
        shape = shape,
        border =
            if (showSizeEffects) {
                BorderStroke(effects.title.borderWidth, effects.title.borderColor)
            } else null
    ) {
        Text(
            text = AppConfig.APP_DISPLAY_NAME,
            fontSize =
                if (showSizeEffects) {
                    effects.title.titleSize
                } else 18.sp,
            fontWeight = FontWeight.Bold,
            color = effects.title.titleColor,
            style = TextStyle(shadow = textShadow),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)

        )
    }
}

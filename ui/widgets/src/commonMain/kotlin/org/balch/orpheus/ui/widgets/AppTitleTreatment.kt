package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

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
    // Forces the opaque raised bevel look even with onClick == null. The translucent
    // liquidVizEffects branch reads fine over the app's usual dark chrome, but washes out
    // against a bright/busy visualization (the DJ app's TV title) — the raised plate is opaque
    // enough to survive that without making the title clickable or focusable.
    forceRaised: Boolean = false,
) {
    val liquidState = LocalLiquidState.current
    val shape = RoundedCornerShape(8.dp)
    val raised = onClick != null || forceRaised
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
                    // The opaque plate's chrome base stays fixed; the wash/bevel follow the
                    // selected visualization's own title color — see orpheusRaisedPlate's doc for
                    // why that's the right source (the same color the non-raised branch below
                    // uses for its text).
                    Modifier.orpheusRaisedPlate(
                        shape = shape,
                        accent = effects.title.titleColor,
                    )
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
            // orpheusRaisedPlate draws its own bevel as part of the modifier chain above.
            raised -> null
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

@Preview(name = "Force Raised — non-interactive, opaque")
@Composable
private fun AppTitleTreatmentForceRaisedPreview() {
    OrpheusTheme {
        AppTitleTreatment(title = "Orphic DJ", forceRaised = true)
    }
}

/**
 * Same raised/opaque treatment as [AppTitleTreatmentForceRaisedPreview], but with a non-default
 * visualization palette passed in — proves the plate's fill/bevel now follow [effects] instead of
 * a fixed brand purple/cyan regardless of what's on screen (a stand-in pink palette, not tied to
 * any real viz file, since this widget doesn't depend on the visualizations module).
 */
@Preview(name = "Force Raised — viz palette (pink)")
@Composable
private fun AppTitleTreatmentForceRaisedVizPalettePreview() {
    OrpheusTheme {
        AppTitleTreatment(
            title = "Orphic DJ",
            effects = VisualizationLiquidEffects(
                title = CenterPanelStyle(
                    titleColor = OrpheusColors.synthPink,
                    borderColor = OrpheusColors.synthPink.copy(alpha = 0.45f),
                ),
            ),
            forceRaised = true,
        )
    }
}

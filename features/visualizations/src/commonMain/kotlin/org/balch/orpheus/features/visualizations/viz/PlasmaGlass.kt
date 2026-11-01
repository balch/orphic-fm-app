package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors

private val PlasmaTitle = CenterPanelStyle(
    scope = VisualizationLiquidScope(
        saturation = 1f,
        contrast = .9f,
        dispersion = .2f,
        refraction = 0.2f,
    ),
    titleColor = OrpheusColors.neonCyan,
    borderColor = OrpheusColors.neonMagenta.copy(alpha = 0.4f),
    borderWidth = 3.dp,
    titleElevation = 12.dp,
)

/**
 * Panel glass for the plasma visualizations (Orphogram, Orphograph). The plasma fills the screen
 * at full brightness. Where panels cover the screen (a phone) the glass is frosted and dark so
 * labels read over it; on the large-screen dock the panels float over the picture, so the glass
 * there is lighter and lets more of it through. Only a faint bend at the edges either way.
 */
internal val PlasmaGlass = VisualizationLiquidEffects(
    frostSmall = 4f,
    frostMedium = 6f,
    frostLarge = 8f,
    tintAlpha = 0.1f,
    top = VisualizationLiquidScope(
        saturation = .3f,
        contrast = .85f,
        dispersion = .1f,
        refraction = 0.1f,
    ),
    bottom = VisualizationLiquidScope(
        saturation = .3f,
        contrast = .85f,
        dispersion = .15f,
        refraction = 0.15f,
    ),
    title = PlasmaTitle,
    docked = VisualizationLiquidEffects(
        frostSmall = 11f,
        frostMedium = 11f,
        frostLarge = 11f,
        tintAlpha = 0.3f,
        top = VisualizationLiquidScope(
            saturation = .45f,
            contrast = .9f,
            dispersion = .1f,
            refraction = 0.1f,
        ),
        bottom = VisualizationLiquidScope(
            saturation = .45f,
            contrast = .9f,
            dispersion = .15f,
            refraction = 0.15f,
        ),
        title = PlasmaTitle,
    ),
)

package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors

internal val PlasmaTitle = CenterPanelStyle(
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
 * The nav bars' glass on every layout: a dark, muted strip that holds its icons and labels over
 * the plasma. The bars tint and edge themselves with this title colour, so it is the void's dark
 * rather than the title's neon, which washed the bar cyan over magenta plasma.
 */
internal val PlasmaBars = VisualizationLiquidEffects(
    frostSmall = 6f,
    frostMedium = 6f,
    frostLarge = 6f,
    tintAlpha = 0.65f,
    top = VisualizationLiquidScope(saturation = .4f, contrast = .9f, dispersion = .05f, refraction = 0.05f),
    bottom = VisualizationLiquidScope(saturation = .4f, contrast = .9f, dispersion = .05f, refraction = 0.05f),
    title = PlasmaTitle.copy(titleColor = OrpheusColors.darkVoid),
)

/** The large-screen dock's panels: they float over the plasma and hold their labels on their own, so frosted and dark. */
internal val DockedPanels = VisualizationLiquidEffects(
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
    bars = PlasmaBars,
)

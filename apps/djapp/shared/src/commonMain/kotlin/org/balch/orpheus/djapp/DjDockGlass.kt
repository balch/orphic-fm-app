package org.balch.orpheus.djapp

import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects

/**
 * The glass the dock's panels and bars use for the playing visualization.
 *
 * A visualization that sets `hidesPanelsWhenIdle` may thin its glass out on the grounds that the
 * panels leave shortly anyway. The dock never fades them (see [fadesPanelsWhenIdle]), so there
 * that glass is permanent and has to hold labels on its own. Only such a visualization is
 * touched; every other one reaches the dock exactly as it reaches a phone.
 */
internal fun dockLiquidEffects(
    effects: VisualizationLiquidEffects,
    vizHidesPanelsWhenIdle: Boolean,
): VisualizationLiquidEffects =
    if (vizHidesPanelsWhenIdle) effects.withDockGlassFloor() else effects

/**
 * Raises glass that is too thin to stand permanently. A floor, never a replacement: a value
 * already at least this strong passes through unchanged.
 */
internal fun VisualizationLiquidEffects.withDockGlassFloor(): VisualizationLiquidEffects = copy(
    frostSmall = frostSmall.coerceAtLeast(8f),
    frostMedium = frostMedium.coerceAtLeast(10f),
    frostLarge = frostLarge.coerceAtLeast(12f),
    tintAlpha = tintAlpha.coerceAtLeast(0.3f),
    top = top.copy(saturation = top.saturation.coerceAtMost(0.5f)),
    bottom = bottom.copy(saturation = bottom.saturation.coerceAtMost(0.5f)),
)

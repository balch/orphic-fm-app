package org.balch.orpheus.djapp

import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DjDockGlassTest {

    // What a fading visualization may ship: no fill and an undimmed picture behind the labels.
    private val flat = VisualizationLiquidScope(saturation = 1f, contrast = 1f)
    private val thin = VisualizationLiquidEffects(tintAlpha = 0f, top = flat, bottom = flat)

    @Test
    fun `a visualization that does not fade its panels is untouched`() {
        assertSame(thin, dockLiquidEffects(thin, vizHidesPanelsWhenIdle = false))
    }

    @Test
    fun `thin glass gets a fill and a dimmed picture on the dock`() {
        val docked = dockLiquidEffects(thin, vizHidesPanelsWhenIdle = true)
        assertTrue(docked.tintAlpha > 0f, "no fill behind the labels")
        assertTrue(docked.top.saturation < 1f, "picture behind the panels is undimmed")
    }

    @Test
    fun `the floor never weakens glass that is already stronger`() {
        val heavy = VisualizationLiquidEffects.Off.copy(tintAlpha = 0.5f)
        assertEquals(heavy, heavy.withDockGlassFloor())
    }

    @Test
    fun `the title treatment keeps the visualization's own colours`() {
        assertEquals(thin.title, dockLiquidEffects(thin, vizHidesPanelsWhenIdle = true).title)
    }
}

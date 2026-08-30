package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A 1080p Android TV reports 960x540dp — the values a real device measures. Android TV picks
 * that density deliberately so ordinary dp/sp already read correctly at couch distance.
 */
class DjLayoutModeTest {

    @Test
    fun realTvDimensionsAreLargeScreen() {
        assertEquals(DjLayoutMode.LargeScreen, determineLayoutMode(960.dp, 540.dp))
    }

    @Test
    fun widenedTvCanvasStaysLargeScreen() {
        // tvDensityScale widens the canvas to TvDesignWidthDp so the fixed-width dock panels fit.
        // The widened reading must still clear the LargeScreen thresholds, or the dock vanishes.
        assertEquals(DjLayoutMode.LargeScreen, determineLayoutMode(1280.dp, 720.dp))
    }

    @Test
    fun narrowingTheCanvasWouldCostTvMode() {
        // Documents why tvDensityScale must stay below 1f. Scaling density UP shrinks the measured
        // canvas (fixed pixels / larger density = fewer dp), dropping it under LargeScreenMinWidth
        // and silently taking the TV dock with it.
        assertNotEquals(DjLayoutMode.LargeScreen, determineLayoutMode(720.dp, 405.dp))
    }
}

package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        // largeScreenDensityScale widens the canvas to LargeScreenDesignWidthDp so the fixed-width dock panels fit.
        // The widened reading must still clear the LargeScreen thresholds, or the dock vanishes.
        assertEquals(DjLayoutMode.LargeScreen, determineLayoutMode(1280.dp, 720.dp))
    }

    @Test
    fun unfoldedFoldHeldUprightFitsThePair() {
        // SM-F976U inner display upright: 752 x 834.7dp. Stays Portrait (under 900dp) and has
        // room for two ~376dp panels.
        val mode = determineLayoutMode(752.dp, 834.667.dp)
        assertEquals(DjLayoutMode.Portrait, mode)
        assertTrue(fitsPortraitPair(mode, 752.dp))
    }

    @Test
    fun phonePortraitKeepsTheSinglePanel() {
        val mode = determineLayoutMode(412.dp, 915.dp)
        assertEquals(DjLayoutMode.Portrait, mode)
        assertFalse(fitsPortraitPair(mode, 412.dp))
    }

    @Test
    fun widthsWhereTimerClipsDoNotGetAPair() {
        // renderPortraitPair measured Timer's edge knobs clipping at 660dp and touching the edge
        // at 680dp. A pair must not appear anywhere in that band.
        assertFalse(fitsPortraitPair(DjLayoutMode.Portrait, 660.dp))
        assertFalse(fitsPortraitPair(DjLayoutMode.Portrait, 680.dp))
        assertFalse(fitsPortraitPair(DjLayoutMode.Portrait, 699.dp))
        assertTrue(fitsPortraitPair(DjLayoutMode.Portrait, 700.dp))
    }

    @Test
    fun thePairIsAPortraitOnlyLayout() {
        // Landscape and the dock have their own layouts; a wide width alone must not claim them.
        assertFalse(fitsPortraitPair(DjLayoutMode.Landscape, 834.dp))
        assertFalse(fitsPortraitPair(DjLayoutMode.LargeScreen, 1280.dp))
    }

    @Test
    fun widePortraitIsStillPortraitForTheLandscapeChecks() {
        // Callers read `mode != Portrait` as "landscape" (rail nav, two-column layout). The pair
        // is carried as a separate flag precisely so this stays Portrait and those checks hold.
        val mode = determineLayoutMode(752.dp, 834.667.dp)
        assertTrue(fitsPortraitPair(mode, 752.dp))
        assertFalse(mode != DjLayoutMode.Portrait, "wide portrait must not read as landscape")
    }

    @Test
    fun narrowingTheCanvasWouldCostTvMode() {
        // Documents why largeScreenDensityScale must stay below 1f. Scaling density UP shrinks the measured
        // canvas (fixed pixels / larger density = fewer dp), dropping it under LargeScreenMinWidth
        // and silently taking the TV dock with it.
        assertNotEquals(DjLayoutMode.LargeScreen, determineLayoutMode(720.dp, 405.dp))
    }
}

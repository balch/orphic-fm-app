package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DjLayoutTest {
    private val foldHinge = Hinge(topPx = 1128, bottomPx = 1128)

    @Test
    fun aHingeWinsOverEverySizeRule() {
        // Sideways Fold: native size would be Landscape, scaled size LargeScreen.
        assertIs<DjLayout.Tabletop>(resolveLayout(835.dp, 752.dp, hinge = foldHinge))
        assertIs<DjLayout.Tabletop>(resolveLayout(1280.dp, 1153.dp, hinge = foldHinge))
    }

    @Test
    fun foldTabletopGetsThePair() {
        assertEquals(DjLayout.Tabletop(foldHinge, pair = true), resolveLayout(835.dp, 752.dp, hinge = foldHinge))
    }

    @Test
    fun narrowTabletopGetsOnePanel() {
        // Z Flip width: two panels would clip Timer.
        assertEquals(DjLayout.Tabletop(foldHinge, pair = false), resolveLayout(411.dp, 1005.dp, hinge = foldHinge))
    }

    @Test
    fun tabletopPairUsesThePortraitPairThreshold() {
        assertFalse(resolveLayout(699.dp, 900.dp, hinge = foldHinge).showsPair())
        assertTrue(resolveLayout(700.dp, 900.dp, hinge = foldHinge).showsPair())
    }

    @Test
    fun realTvDimensionsAreLargeScreen() {
        assertEquals(DjLayout.LargeScreen, resolveLayout(960.dp, 540.dp))
    }

    @Test
    fun narrowingTheCanvasWouldCostTvMode() {
        // Why the TV density scale must stay below 1f: scaling up shrinks the canvas under the gate.
        assertEquals(DjLayout.Landscape, resolveLayout(720.dp, 405.dp))
    }

    @Test
    fun widthsWhereTimerClipsDoNotGetAPair() {
        assertEquals(DjLayout.Portrait, resolveLayout(660.dp, 900.dp))
        assertEquals(DjLayout.Portrait, resolveLayout(680.dp, 900.dp))
        assertEquals(DjLayout.Portrait, resolveLayout(699.dp, 900.dp))
        assertEquals(DjLayout.PortraitPair, resolveLayout(700.dp, 900.dp))
        // A near-square desktop window under the dock stacks the pair rather than landscape.
        assertEquals(DjLayout.PortraitPair, resolveLayout(766.dp, 750.dp, tvModeAllowed = false))
        assertEquals(DjLayout.PortraitPair, resolveLayout(766.dp, PortraitPairMinHeight))
        assertEquals(DjLayout.Landscape, resolveLayout(766.dp, PortraitPairMinHeight - 1.dp))
        assertEquals(DjLayout.Landscape, resolveLayout(699.dp, 690.dp))
    }

    @Test
    fun tabletopKeepsPortraitChrome() {
        assertFalse(DjLayout.Tabletop(foldHinge, pair = true).usesLandscapeChrome())
        assertFalse(DjLayout.Portrait.usesLandscapeChrome())
        assertFalse(DjLayout.PortraitPair.usesLandscapeChrome())
        assertTrue(DjLayout.Landscape.usesLandscapeChrome())
        assertTrue(DjLayout.LargeScreen.usesLandscapeChrome())
    }

    @Test
    fun onlyThePairLayoutsShowThePair() {
        assertTrue(DjLayout.PortraitPair.showsPair())
        assertTrue(DjLayout.Tabletop(foldHinge, pair = true).showsPair())
        assertFalse(DjLayout.Tabletop(foldHinge, pair = false).showsPair())
        assertFalse(DjLayout.Portrait.showsPair())
        assertFalse(DjLayout.Landscape.showsPair())
        assertFalse(DjLayout.LargeScreen.showsPair())
    }

    @Test
    fun aSlotWithRoomKeepsTheFullGrid() {
        assertEquals(120.dp, pulsarGridHeightFor(slot = 350.dp, full = 120.dp))
        assertEquals(120.dp, pulsarGridHeightFor(slot = 500.dp, full = 120.dp))
    }

    @Test
    fun closedDuoSlotTakesItsShortfallOffTheGrid() {
        // 60% of the 512dp column under the header: 43dp short of a clean panel.
        assertEquals(77.dp, pulsarGridHeightFor(slot = 307.dp, full = 120.dp))
    }

    @Test
    fun theGridNeverShrinksPastItsMinimum() {
        assertEquals(PulsarGridMinHeight, pulsarGridHeightFor(slot = 200.dp, full = 120.dp))
    }
}

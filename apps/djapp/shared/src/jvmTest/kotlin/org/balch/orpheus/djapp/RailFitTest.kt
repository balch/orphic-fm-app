package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RailFitTest {
    // labelSmall's 16sp line under each tab and the ring: at font scale 1, and at the Fold 8's 1.3.
    private val line = 16.dp
    private val foldLine = 20.8.dp

    @Test fun theUsersDesktopWindowKeepsTheFullRing() = assertEquals(RailTransport.Standard, railTransportFor(606.dp, 4, line))
    @Test fun theDesktopWindowKeepsTheFullRingAtTheFoldsFontScaleToo() =
        assertEquals(RailTransport.Standard, railTransportFor(606.dp, 4, foldLine))

    @Test fun aSidewaysPixelKeepsTheFullRing() = assertEquals(RailTransport.Standard, railTransportFor(412.dp, 4, line))
    @Test fun aSidewaysPixelWithAiKeepsTheFullRing() = assertEquals(RailTransport.Standard, railTransportFor(412.dp, 5, line))
    @Test fun aSidewaysPixelAtTheFoldsFontScaleKeepsTheFullRing() =
        assertEquals(RailTransport.Standard, railTransportFor(412.dp, 4, foldLine))

    // The user's report: 840x360dp sideways at font scale 1.3.
    @Test fun theFoldCoverScreenGoesCompact() = assertEquals(RailTransport.Compact, railTransportFor(360.dp, 4, foldLine))

    // At font scale 1 the same height keeps the full ring: its tabs are 5dp shorter each.
    @Test fun atFontScaleOneTheCoverScreenKeepsTheFullRing() =
        assertEquals(RailTransport.Standard, railTransportFor(360.dp, 4, line))

    @Test fun belowTheCompactFloorTheRailStaysCompact() {
        val floor = railMinHeight(RailTransport.Compact, 4, foldLine)
        assertTrue(floor <= 360.dp, "the compact floor $floor is over the cover screen's 360dp")
        assertEquals(RailTransport.Compact, railTransportFor(floor - 30.dp, 4, foldLine))
    }

    @Test fun eachTierFitsFromItsOwnFloor() {
        RailTransport.entries.forEach { tier ->
            assertEquals(tier, railTransportFor(railMinHeight(tier, 4, foldLine), 4, foldLine))
        }
    }

    @Test fun theCompactRailTightensItsTabsAndShrinksItsRing() {
        assertTrue(RailTransport.Compact.tabGap < RailTransport.Standard.tabGap)
        assertEquals(48.dp, RailTransport.Compact.ringSize)
        assertEquals(RailRingSize, RailTransport.Standard.ringSize)
    }
}

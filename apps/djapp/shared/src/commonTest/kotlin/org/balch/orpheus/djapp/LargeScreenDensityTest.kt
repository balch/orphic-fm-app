package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real measured geometry, not invented numbers. The Fold figures come from an attached
 * SM-F976U (Galaxy Z Fold, Android 17): `adb shell wm size` reports 2256x2504 on the inner
 * display and 1080x2520 on the cover, both at `wm density` 480 (3.0x).
 *
 * The three surfaces one folding device presents, and the layout each must produce:
 *   cover display        360 x 840dp    -> Portrait   (phone)
 *   inner, upright       752 x 834.7dp  -> PortraitPair (mobile view, two panels)
 *   inner, sideways      834.7 x 752dp  -> LargeScreen (dock)
 */
class LargeScreenDensityTest {

    // 2256/3 = 752dp, 2504/3 = 834.667dp.
    private val foldInnerLongDp = 834.667f
    private val foldInnerShortDp = 752f
    private val foldInnerSmallestWidthDp = 752

    @Test
    fun foldInnerLandscapeWidensToTheDesignCanvas() {
        val scale = largeScreenDensityScale(
            widthDp = foldInnerLongDp,
            heightDp = foldInnerShortDp,
            smallestWidthDp = foldInnerSmallestWidthDp,
            isTelevision = false,
        )
        // 834.667 / 1280 = 0.6521
        assertEquals(0.652f, scale, absoluteTolerance = 0.001f)
    }

    @Test
    fun foldInnerLandscapeDocks() {
        val scale = largeScreenDensityScale(
            foldInnerLongDp, foldInnerShortDp, foldInnerSmallestWidthDp, isTelevision = false,
        )
        val width = (foldInnerLongDp / scale).dp
        val height = (foldInnerShortDp / scale).dp

        assertEquals(1280f, width.value, absoluteTolerance = 0.5f)
        assertEquals(DjLayout.LargeScreen, resolveLayout(width, height))
    }

    @Test
    fun foldInnerPortraitKeepsTheMobileView() {
        // Turning the unfolded device upright is the demo's other half: same hardware, phone
        // layout. The scale must stand down so the canvas stays under LargeScreenMinWidth.
        val scale = largeScreenDensityScale(
            widthDp = foldInnerShortDp,
            heightDp = foldInnerLongDp,
            smallestWidthDp = foldInnerSmallestWidthDp,
            isTelevision = false,
        )
        assertEquals(1f, scale)
        // Upright keeps native density; at 752dp that is the two-panel portrait, not the dock.
        assertEquals(
            DjLayout.PortraitPair,
            resolveLayout((foldInnerShortDp / scale).dp, (foldInnerLongDp / scale).dp),
        )
    }

    @Test
    fun unscaledFoldLandscapeWouldNotDock() {
        // Documents the bug this change fixes: 834.7dp falls 65dp short of LargeScreenMinWidth,
        // which is why the device showed the two-column landscape layout instead of the dock.
        assertEquals(
            DjLayout.Landscape,
            resolveLayout(foldInnerLongDp.dp, foldInnerShortDp.dp),
        )
    }

    @Test
    fun foldCoverDisplayIsUntouched() {
        // 1080x2520 at density 480 = 360x840dp, smallestWidth 360. Folding the phone must still
        // give the phone layout.
        assertEquals(
            1f,
            largeScreenDensityScale(360f, 840f, smallestWidthDp = 360, isTelevision = false),
        )
    }

    @Test
    fun ordinaryPhoneLandscapeIsUntouched() {
        // 892x412dp sits deliberately just under the LargeScreen gate; a phone turned sideways
        // is not a tablet and must not start widening its canvas.
        assertEquals(
            1f,
            largeScreenDensityScale(892f, 412f, smallestWidthDp = 412, isTelevision = false),
        )
    }

    @Test
    fun tabletAlreadyAtTheDesignWidthIsUntouched() {
        // Pixel Tablet: 2560x1600 at density 320 = 1280x800dp. It already docks with no help, and
        // scaling above 1f would NARROW its canvas back toward the cliff.
        assertEquals(
            1f,
            largeScreenDensityScale(1280f, 800f, smallestWidthDp = 800, isTelevision = false),
        )
    }

    @Test
    fun tabletWiderThanTheDesignWidthIsNeverScaledUp() {
        val scale = largeScreenDensityScale(1376f, 1032f, smallestWidthDp = 1032, isTelevision = false)
        assertTrue(scale <= 1f, "scaling up narrows the canvas and can cost the dock")
        assertEquals(1f, scale)
    }

    @Test
    fun screenTooNarrowToScaleSafelyKeepsItsNativeDensity() {
        // A tablet-class screen at the bottom of the eligibility band would need 600/1280 = 0.469
        // to reach the design canvas, shrinking a 48dp target to roughly 4mm. The floor rejects
        // that outright rather than half-applying it.
        assertEquals(
            1f,
            largeScreenDensityScale(600f, 600f, smallestWidthDp = 600, isTelevision = false),
        )
    }

    @Test
    fun theFloorSitsAt768dpOfLandscapeWidth() {
        // 0.6 x 1280 = 768. Either side of the boundary, so a future change to either constant
        // that moves this line has to say so out loud.
        assertEquals(
            0.6f,
            largeScreenDensityScale(768f, 700f, smallestWidthDp = 700, isTelevision = false),
            absoluteTolerance = 0.001f,
        )
        assertEquals(
            1f,
            largeScreenDensityScale(767f, 700f, smallestWidthDp = 700, isTelevision = false),
        )
    }

    @Test
    fun foldClearsTheFloorWithRoomToSpare() {
        // 0.652 against a 0.6 floor. Documents that the shipped device is not sitting on the
        // boundary, so a small future tweak to either constant will not silently un-dock it.
        val scale = largeScreenDensityScale(
            foldInnerLongDp, foldInnerShortDp, foldInnerSmallestWidthDp, isTelevision = false,
        )
        assertTrue(scale > 0.6f, "Fold scale $scale must clear the floor, not land on it")
    }

    @Test
    fun televisionStillReachesTheDesignCanvas() {
        // Preserves the shipped TV behaviour: 1080p TV reports 960x540dp, smallestWidth 540 —
        // below the tablet qualifier, so it qualifies on the television flag alone.
        val scale = largeScreenDensityScale(960f, 540f, smallestWidthDp = 540, isTelevision = true)
        assertEquals(0.75f, scale, absoluteTolerance = 0.001f)
        assertEquals(1280f, 960f / scale, absoluteTolerance = 0.5f)
    }

    @Test
    fun tabletopKeepsNativeDensity() {
        // Sideways scales to 0.652; half-folded, the flat half is the touch surface, so no scale.
        assertEquals(
            1f,
            largeScreenDensityScale(
                foldInnerLongDp, foldInnerShortDp, foldInnerSmallestWidthDp,
                isTelevision = false, tabletop = true,
            ),
        )
    }
}

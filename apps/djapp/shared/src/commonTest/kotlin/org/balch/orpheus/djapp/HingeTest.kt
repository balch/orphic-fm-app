package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SM-F976U inner display held sideways and half-folded: 2504x2256px at density 3.0, crease at
 * 1128px. With Samsung's flex panel on, the window shrinks to the top 1128px.
 */
class HingeTest {
    private val density = 3f

    @Test
    fun foldTabletopIsAHinge() {
        assertEquals(Hinge(1128, 1128), tabletopHingeOf(true, true, true, 1128, 1128, 2256, density))
    }

    @Test
    fun flatIsNotAHinge() {
        assertNull(tabletopHingeOf(false, true, true, 1128, 1128, 2256, density))
    }

    @Test
    fun bookPostureIsOutOfScope() {
        assertNull(tabletopHingeOf(true, false, true, 1128, 1128, 2256, density))
    }

    @Test
    fun nonSeparatingFoldIsNotAHinge() {
        assertNull(tabletopHingeOf(true, true, false, 1128, 1128, 2256, density))
    }

    @Test
    fun flexPanelWindowRejectsTheHingeOnItsEdge() {
        // The crease is the window's bottom edge, leaving a 0dp flat half.
        assertNull(tabletopHingeOf(true, true, true, 1128, 1128, 1128, density))
    }

    @Test
    fun offCentreHingeKeepsItsRealPosition() {
        // Off-centre (the middle is 1128px) with a 30px fold; both halves clear any minimum up to 360dp.
        assertEquals(Hinge(1140, 1170), tabletopHingeOf(true, true, true, 1140, 1170, 2256, density))
    }

    @Test
    fun aHingeLeavingATooSmallFlatHalfIsRejected() {
        // (2256 - 1330) / 3 = 308.67dp flat half: under the minimum, so no tabletop split.
        assertNull(tabletopHingeOf(true, true, true, 1300, 1330, 2256, density))
    }

    @Test
    fun halvesAreCheckedAgainstTheMinimum() {
        val min = TabletopMinHalfHeight
        assertTrue(splitsIntoUsableHalves(min, min, min * 2))
        assertFalse(splitsIntoUsableHalves(min - 1.dp, min - 1.dp, min * 2))
    }

    // iPhone Duo inner display in portrait, measured on the iOS 27.1 simulator: 669x951pt at
    // scale 3. The division frame is a 40pt band, two 20pt margins around a zero-height crease.
    @Test
    fun duoTabletopIsAHinge() {
        assertEquals(Hinge(1367, 1487), tabletopHingeOfPoints(true, 455.5, 495.5, 669.0, 951.0, 3.0))
    }

    @Test
    fun duoOpenedFlatIsNotAHinge() {
        // Fully open still reports the division, inactive.
        assertNull(tabletopHingeOfPoints(false, 455.5, 495.5, 669.0, 951.0, 3.0))
    }

    @Test
    fun pointFoldTallerThanWideIsBookPosture() {
        assertNull(tabletopHingeOfPoints(true, 0.0, 951.0, 40.0, 951.0, 3.0))
    }

    @Test
    fun pointFoldLeavingATooSmallHalfIsRejected() {
        // 300pt above the fold is under the 330dp minimum.
        assertNull(tabletopHingeOfPoints(true, 300.0, 340.0, 669.0, 951.0, 3.0))
    }

    @Test
    fun foldHalvesClearTheMinimum() {
        // 1128px / 3 = 376dp a side. The minimum must stay below this or the Fold loses tabletop.
        assertTrue(splitsIntoUsableHalves(376.dp, 376.dp, 752.dp))
    }
}

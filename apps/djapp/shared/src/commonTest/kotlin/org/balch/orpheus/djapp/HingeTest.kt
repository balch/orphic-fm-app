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

    // Point-based folds (iOS). No device numbers exist yet: an 800pt-tall view at scale 3 with a
    // 20pt fold band across a 600pt-wide view stands in until the real geometry is known.
    @Test
    fun pointsScaleToPixels() {
        assertEquals(Hinge(1170, 1230), tabletopHingeOfPoints(true, 390.0, 410.0, 600.0, 800.0, 3.0))
    }

    @Test
    fun inactivePointFoldIsNotAHinge() {
        assertNull(tabletopHingeOfPoints(false, 390.0, 410.0, 600.0, 800.0, 3.0))
    }

    @Test
    fun pointFoldTallerThanWideIsBookPosture() {
        assertNull(tabletopHingeOfPoints(true, 0.0, 800.0, 20.0, 800.0, 3.0))
    }

    @Test
    fun pointFoldLeavingATooSmallHalfIsRejected() {
        // 300pt above the fold is under the 330dp minimum.
        assertNull(tabletopHingeOfPoints(true, 300.0, 320.0, 600.0, 800.0, 3.0))
    }

    @Test
    fun foldHalvesClearTheMinimum() {
        // 1128px / 3 = 376dp a side. The minimum must stay below this or the Fold loses tabletop.
        assertTrue(splitsIntoUsableHalves(376.dp, 376.dp, 752.dp))
    }
}

package org.balch.orpheus.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingCalloutCaretTest {
    @Test
    fun aCaretWithNoTargetSitsInTheMiddle() = assertEquals(100f, caretCentre(null, 200f, 15f))

    @Test
    fun aCaretGoesWhereItIsAimed() = assertEquals(60f, caretCentre(60f, 200f, 15f))

    @Test
    fun aCaretStaysClearOfTheCorners() {
        assertEquals(15f, caretCentre(-40f, 200f, 15f))
        assertEquals(185f, caretCentre(500f, 200f, 15f))
    }

    @Test
    fun aCaretOnAnEdgeTooShortForItsCornersSitsInTheMiddle() = assertEquals(10f, caretCentre(3f, 20f, 15f))
}

package org.balch.orpheus.djapp

import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegibleTextTest {

    @Test
    fun foldScaleGrowsChatBody() {
        val sp = legibleSp(LegibleText.CHAT_BODY, 0.652f).value
        assertTrue(abs(sp - 23.0f) < 0.05f, "expected about 23sp, was $sp")
    }

    @Test
    fun nativeScaleIsIdentity() {
        assertEquals(13.sp, legibleSp(13f, 1f))
    }

    @Test
    fun scaleIsClamped() {
        assertEquals(20.sp, legibleSp(10f, 0.2f))
    }
}

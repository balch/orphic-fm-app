package org.balch.orpheus.features.mediapipe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandGlassOverlayTest {

    @Test
    fun `glass renders only when enabled and sourced and fully landmarked`() {
        assertTrue(rendersGlass(glassEnabled = true, hasLiquidState = true, landmarkCount = 21))
    }

    @Test
    fun `degrades to the fallback when glass is disabled`() {
        assertFalse(rendersGlass(glassEnabled = false, hasLiquidState = true, landmarkCount = 21))
    }

    @Test
    fun `degrades when there is no liquid source to refract`() {
        assertFalse(rendersGlass(glassEnabled = true, hasLiquidState = false, landmarkCount = 21))
    }

    @Test
    fun `degrades when landmarks are incomplete`() {
        assertFalse(rendersGlass(glassEnabled = true, hasLiquidState = true, landmarkCount = 20))
    }
}

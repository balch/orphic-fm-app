package org.balch.orpheus.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class BenderSpringTest {
    @Test
    fun benderSpringStagesMatchTheFader() {
        val full = benderSpringStages(100f, 1f)
        assertEquals(listOf(480, 360, 360), full.map { it.durationMillis })
        // The widget's 0.3f and 0.4f products land an ulp off the round values.
        listOf(-30f, 12f, 0f).zip(full).forEach { (want, stage) ->
            assertEquals(want, stage.target, 1e-4f)
        }
        assertEquals(listOf(160, 120, 120), benderSpringStages(100f, 0f).map { it.durationMillis })
    }
}

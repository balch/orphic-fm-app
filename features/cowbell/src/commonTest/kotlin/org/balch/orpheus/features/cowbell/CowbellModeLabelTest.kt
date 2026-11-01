package org.balch.orpheus.features.cowbell

import kotlin.test.Test
import kotlin.test.assertEquals
import org.balch.orpheus.plugins.cowbell.CowbellMode

class CowbellModeLabelTest {

    @Test
    fun modeLabelsReadTapShakeHold() {
        assertEquals(listOf("Tap", "Shake", "Hold"), CowbellMode.entries.map { it.label })
    }
}

package org.balch.orpheus.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "org.balch.orpheus",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        
        // Add critical user journeys here to optimize them
        // For now, we focus on startup and initial draw
    }
}

package org.balch.orpheus.ui.viz

import androidx.compose.ui.graphics.Color

/**
 * A basic visual element used in various visualizations.
 */
data class Blob(
    val id: Int,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var radius: Float = 0f,
    var color: Color = Color.White,
    var energy: Float = 0f,
    var alpha: Float = 1f,
    var age: Float = 0f,
    var voiceIndex: Int = -1
)

package org.balch.orpheus.features.visualizations.viz.fish

import androidx.compose.ui.graphics.Color

/**
 * A fish in the aquarium visualization.
 * Mutable fields are updated in-place each frame for performance.
 * Plain class (not data class) because mutable vars make equals/hashCode unreliable.
 */
class Fish(
    val id: Int,
    val baseColor: Color,
    val baseSize: Float,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var heading: Float = 0f,
    var smoothHeading: Float = 0f,
    var tailPhase: Float = 0f,
    var voiceIndex: Int = 0,
    var energy: Float = 0f,
    var pulseScale: Float = 1f,
    var alpha: Float = 1f,
    var isFadingOut: Boolean = false,
)
package org.balch.orpheus.core.gestures

import kotlinx.serialization.Serializable

@Serializable
enum class PadType { VOICE, DRUM }

/** Size mode for gesture pads. Cycles via double-tap in edit mode. */
@Serializable
enum class PadSizeMode {
    LARGE,  // 1.0× (default)
    MEDIUM, // 0.65×
    SMALL;  // 0.35× (for tucking pads out of the way)

    /** Scale factor relative to LARGE. */
    val scale: Float get() = when (this) {
        LARGE -> 1.0f
        MEDIUM -> 0.65f
        SMALL -> 0.35f
    }

    /** Next mode in the cycle: LARGE → MEDIUM → SMALL → LARGE. */
    fun next(): PadSizeMode = when (this) {
        LARGE -> MEDIUM
        MEDIUM -> SMALL
        SMALL -> LARGE
    }
}

/**
 * Axis-aligned rectangle in normalized [0,1] coordinates.
 * (0,0) = top-left of camera view, (1,1) = bottom-right.
 */
@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * A virtual pad overlaid on the camera view.
 * Can represent a voice trigger (0-11) or a drum trigger (BD=0, SD=1, HH=2).
 */
@Serializable
data class GesturePad(
    val id: String,
    val type: PadType,
    val voiceIndex: Int?,
    val drumType: Int?,
    val bounds: NormalizedRect,
    val label: String,
    val sizeMode: PadSizeMode = PadSizeMode.LARGE,
)

/**
 * Resize a pad's bounds to a new [PadSizeMode], scaling from center.
 * The result is clamped to [0,1] normalized coordinates.
 */
fun GesturePad.resizedBounds(newMode: PadSizeMode): NormalizedRect {
    val currentW = bounds.right - bounds.left
    val currentH = bounds.bottom - bounds.top
    val centerX = bounds.left + currentW / 2f
    val centerY = bounds.top + currentH / 2f

    // Scale relative to current size mode
    val ratio = newMode.scale / sizeMode.scale
    val newW = (currentW * ratio).coerceAtMost(1f)
    val newH = (currentH * ratio).coerceAtMost(1f)

    val newLeft = (centerX - newW / 2f).coerceIn(0f, 1f - newW)
    val newTop = (centerY - newH / 2f).coerceIn(0f, 1f - newH)

    return NormalizedRect(newLeft, newTop, newLeft + newW, newTop + newH)
}

/** Hit-test a point against a list of pads. Returns the first pad that contains the point. */
fun List<GesturePad>.hitTest(x: Float, y: Float): GesturePad? =
    firstOrNull { it.bounds.contains(x, y) }

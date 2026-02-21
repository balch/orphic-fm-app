package org.balch.orpheus.features.mediapipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.balch.orpheus.core.gestures.HAND_CONNECTIONS
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.LandmarkIndex

/**
 * Draws the 21-point hand skeleton with connection lines.
 * Highlights pinch state with a yellow indicator between thumb and index tips.
 */
@Composable
fun HandSkeletonOverlay(
    landmarks: List<HandLandmark>,
    isPinching: Boolean,
    modifier: Modifier = Modifier,
    connectionColor: Color = Color.White,
    landmarkColor: Color = Color.Cyan,
    pinchColor: Color = Color.Yellow,
) {
    if (landmarks.size < 21) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Draw connection lines
        for ((from, to) in HAND_CONNECTIONS) {
            if (from >= landmarks.size || to >= landmarks.size) continue
            val a = landmarks[from]
            val b = landmarks[to]
            drawLine(
                color = connectionColor.copy(alpha = 0.6f),
                start = Offset(a.x * w, a.y * h),
                end = Offset(b.x * w, b.y * h),
                strokeWidth = 2f,
            )
        }

        // Draw landmark points
        for (landmark in landmarks) {
            drawCircle(
                color = landmarkColor,
                radius = 4f,
                center = Offset(landmark.x * w, landmark.y * h),
            )
        }

        // Highlight pinch: draw indicator between thumb tip and index tip
        if (isPinching) {
            val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
            val indexTip = landmarks[LandmarkIndex.INDEX_TIP]
            val pinchCenter = Offset(
                (thumbTip.x + indexTip.x) / 2f * w,
                (thumbTip.y + indexTip.y) / 2f * h,
            )
            drawCircle(
                color = pinchColor.copy(alpha = 0.8f),
                radius = 10f,
                center = pinchCenter,
            )
        }
    }
}

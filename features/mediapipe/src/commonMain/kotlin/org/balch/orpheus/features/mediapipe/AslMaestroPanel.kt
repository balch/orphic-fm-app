package org.balch.orpheus.features.mediapipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.gestures.GestureMode
import org.balch.orpheus.features.mediapipe.shader.CameraEffectCanvas
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * MediaPipe gesture control panel.
 * Camera preview fills the panel with skeleton overlay constrained to the
 * actual image bounds. Toggle switch is a small overlay in the top-left corner.
 */
@Composable
fun AslMaestroPanel(
    feature: MediaPipeFeature,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    CollapsibleColumnPanel(
        modifier = modifier,
        title = "ASL",
        color = OrpheusColors.synthGreen,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        expandedTitle = "Maestro",
        showCollapsedHeader = showCollapsedHeader,
    ) {
        val state by feature.stateFlow.collectAsState()
        val actions = feature.actions

        // Request camera permission when panel expands (content enters composition).
        // If granted and not yet enabled, auto-start tracking.
        val requestCameraPermission = rememberCameraPermissionToggle(
            onPermissionGranted = { if (!state.isEnabled) actions.toggleEnabled() },
            onPermissionDenied = { },
        )
        LaunchedEffect(Unit) {
            requestCameraPermission()
        }

        // Stop tracking when panel leaves composition (e.g., swiped away in pager)
        DisposableEffect(Unit) {
            onDispose {
                if (state.isEnabled) actions.toggleEnabled()
            }
        }

        // Camera fills the entire panel content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val frame = state.cameraFrame

            if (frame != null) {
                val bitmap: ImageBitmap = remember(frame) { frame.toImageBitmap() }
                val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

                // Aspect-fit container — centers the camera image without stretching
                Box(modifier = Modifier.aspectRatio(imageAspect)) {
                    // Camera image with audio-reactive shader effects
                    CameraEffectCanvas(
                        cameraImage = bitmap,
                        engine = feature.engine,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Skeleton overlay per hand — same size as the image, so coordinates align
                    for ((index, hand) in state.hands.withIndex()) {
                        HandSkeletonOverlay(
                            landmarks = hand.landmarks,
                            isPinching = state.gestureStates.getOrNull(index)?.isPinching == true,
                            landmarkColor = if (hand.handedness == org.balch.orpheus.core.gestures.Handedness.LEFT) Color.Cyan else OrpheusColors.synthGreen,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

            } else if (!state.isEnabled) {
                Text(
                    text = "Tracking disabled",
                    color = Color.Gray,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    text = "Waiting for camera...",
                    color = Color.Gray,
                    fontSize = 11.sp,
                )
            }

            // Controls overlaid in top-left corner
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { wantEnabled ->
                        if (wantEnabled) requestCameraPermission() else actions.toggleEnabled()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OrpheusColors.synthGreen,
                        checkedTrackColor = OrpheusColors.synthGreen.copy(alpha = 0.3f),
                    ),
                )
            }

            // ASL selection breadcrumb bar overlaid at bottom
            AslSelectionBar(
                selectedTarget = state.selectedTarget,
                selectedParam = state.selectedParam,
                modePrefix = state.modePrefix,
                interactionPhase = state.interactionPhase,
                gestureMode = state.gestureMode,
                isTracking = state.isTracking || state.gestureStates.isNotEmpty(),
                remoteAdjustArmed = state.remoteAdjustArmed,
                selectedDuoIndex = state.selectedDuoIndex,
                selectedQuadIndex = state.selectedQuadIndex,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun AslMaestroPanelPreview() {
    AslMaestroPanel(
        feature = MediaPipeViewModel.previewFeature(),
        isExpanded = true,
        showCollapsedHeader = false,
    )
}

package org.balch.orpheus.features.mediapipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import org.balch.orpheus.ui.widgets.HorizontalSwitch3Way
import org.balch.orpheus.ui.widgets.Switch3WayState

/**
 * MediaPipe gesture control panel.
 *
 * Three-way mode toggle:
 * - VIZ (left): Transparent — background visualization shows through
 * - OFF (center): Panel disabled
 * - CAMERA (right): Camera feed with hand tracking overlay
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

        // Auto-start camera when switching to CAMERA mode via permission flow
        val requestCameraPermission = rememberCameraPermissionToggle(
            onPermissionGranted = {
                if (state.panelMode != GesturePanelMode.OFF && !state.isEnabled) {
                    actions.toggleEnabled()
                }
            },
            onPermissionDenied = { },
        )

        // Stop tracking when panel leaves composition
        DisposableEffect(Unit) {
            onDispose {
                if (state.isEnabled) actions.toggleEnabled()
            }
        }

        // Map panel mode to three-way switch state
        val switchState = when (state.panelMode) {
            GesturePanelMode.VIZ -> Switch3WayState.START
            GesturePanelMode.OFF -> Switch3WayState.MIDDLE
            GesturePanelMode.CAMERA -> Switch3WayState.END
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (state.panelMode) {
                GesturePanelMode.VIZ -> {
                    // Transparent — viz background shows through, but hands are drawn on top
                    for ((index, hand) in state.hands.withIndex()) {
                        HandSkeletonOverlay(
                            landmarks = hand.landmarks,
                            isPinching = state.gestureStates.getOrNull(index)?.isPinching == true,
                            landmarkColor = if (hand.handedness == org.balch.orpheus.core.gestures.Handedness.LEFT) Color.Cyan else OrpheusColors.synthGreen,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                GesturePanelMode.OFF -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Off",
                            color = Color.Gray.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                        )
                    }
                }

                GesturePanelMode.CAMERA -> {
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

                            Box(modifier = Modifier.aspectRatio(imageAspect)) {
                                CameraEffectCanvas(
                                    cameraImage = bitmap,
                                    engine = feature.engine,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                if (state.gestureMode == GestureMode.KEYBOARD) {
                                    KeyboardOverlay(
                                        pressedKeys = state.pressedKeys,
                                        engineName = state.keyboardEngineName,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

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
                                text = if (state.cameraAvailable) "Tap to start camera" else "Camera not available",
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
                    }
                }
            }

            // Three-way toggle overlaid in top-left corner
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalSwitch3Way(
                    state = switchState,
                    onStateChange = { newState ->
                        val newMode = when (newState) {
                            Switch3WayState.START -> {
                                if (state.cameraAvailable) requestCameraPermission()
                                GesturePanelMode.VIZ
                            }
                            Switch3WayState.MIDDLE -> GesturePanelMode.OFF
                            Switch3WayState.END -> {
                                if (state.cameraAvailable) {
                                    requestCameraPermission()
                                    GesturePanelMode.CAMERA
                                } else {
                                    return@HorizontalSwitch3Way
                                }
                            }
                        }
                        actions.setPanelMode(newMode)
                    },
                    color = OrpheusColors.synthGreen,
                    startText = "VIZ",
                    endText = "CAM",
                )
            }

            // ASL selection breadcrumb bar overlaid at bottom (when tracking is active)
            if (state.panelMode == GesturePanelMode.CAMERA || state.panelMode == GesturePanelMode.VIZ) {
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

package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCameraPermissionToggle(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit = onPermissionGranted

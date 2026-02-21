package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable

/**
 * Returns a callback that, when invoked, ensures camera permission is granted
 * before calling [onPermissionGranted]. On platforms without a permission model
 * (JVM, wasmJs), the granted callback fires immediately.
 */
@Composable
expect fun rememberCameraPermissionToggle(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit

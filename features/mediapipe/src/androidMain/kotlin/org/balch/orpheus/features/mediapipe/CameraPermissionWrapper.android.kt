package org.balch.orpheus.features.mediapipe

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberCameraPermissionToggle(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentGranted = rememberUpdatedState(onPermissionGranted)
    val currentDenied = rememberUpdatedState(onPermissionDenied)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) currentGranted.value() else currentDenied.value()
    }

    return {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            currentGranted.value()
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

package org.balch.orpheus.features.mediapipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraPermissionToggle(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit {
    val currentGranted = rememberUpdatedState(onPermissionGranted)
    val currentDenied = rememberUpdatedState(onPermissionDenied)

    return {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> currentGranted.value()
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) currentGranted.value() else currentDenied.value()
                    }
                }
            }
            else -> currentDenied.value()
        }
    }
}

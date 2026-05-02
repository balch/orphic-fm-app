package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.ImageBitmap

// Android consumes the ImageBitmap directly inside the foreground service
// (asAndroidBitmap → setLargeIcon). No PNG encode required.
actual fun ImageBitmap.toPngBytes(): ByteArray? = null

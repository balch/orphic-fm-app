package org.balch.orpheus.djapp.ai

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

internal actual suspend fun Clipboard.copyPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText("prompt", text)))
}

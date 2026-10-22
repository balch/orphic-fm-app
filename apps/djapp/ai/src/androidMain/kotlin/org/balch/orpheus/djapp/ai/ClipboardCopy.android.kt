package org.balch.orpheus.djapp.ai

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

internal actual suspend fun Clipboard.copyPlainText(text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("prompt", text)
    // The key is a compile-time string, so older releases just ignore the extra.
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    setClipEntry(ClipEntry(clip))
}

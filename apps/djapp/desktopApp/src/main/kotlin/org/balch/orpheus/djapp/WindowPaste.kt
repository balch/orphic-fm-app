package org.balch.orpheus.djapp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.balch.orpheus.djapp.share.SharedTextInbox
import org.balch.orpheus.djapp.share.looksLikeSongPaste
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

private val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

internal fun isPasteShortcut(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.V &&
        (if (isMac) event.isMetaPressed else event.isCtrlPressed)

/** Offers song-sized [text] to the inbox; returns true only when it did. */
internal fun offerClipboard(text: String?, inbox: SharedTextInbox): Boolean {
    if (text == null || !looksLikeSongPaste(text)) return false
    inbox.offer(text)
    return true
}

/** The system clipboard's text, or null when it holds something else or cannot be read. */
internal fun readClipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

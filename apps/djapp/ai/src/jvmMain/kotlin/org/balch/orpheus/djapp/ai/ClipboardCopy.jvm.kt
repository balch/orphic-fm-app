package org.balch.orpheus.djapp.ai

import androidx.compose.ui.platform.Clipboard
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Straight to AWT: Compose desktop's Clipboard is a thin wrapper over the same system
// clipboard, but its ClipEntry constructor is still @ExperimentalComposeUiApi in CMP
// 1.11.1 — plain AWT does the identical thing with zero experimental surface.
internal actual suspend fun Clipboard.copyPlainText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
